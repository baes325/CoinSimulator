package com.team.coin_simulator.backtest;

import DAO.UpbitWebSocketDao;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 백테스팅 전용 시간 엔진
 *
 * ■ 동작 원리
 *   - ScheduledExecutorService 로 매 1초마다 tick()을 실행합니다.
 *   - tick() 에서 currentSimTime을 speed.minutesPerTick 만큼 전진시킵니다.
 *   - 리스너(Panel, Chart 등)에 이벤트를 브로드캐스트합니다.
 *
 * ■ 종료 1주일 전 규칙
 *   - 배속을 SPEED_1X / SPEED_10X 로 강제 다운그레이드합니다.
 *   - 포지션이 있으면 "정리하시겠습니까?" 알림을 한 번만 발생시킵니다.
 *
 * ■ 세션 종료
 *   - currentSimTime >= endSimTime 에 도달하면 세션을 종료합니다.
 */
public class BacktestTimeController {

    // ── 싱글톤 ──────────────────────────────────────
    private static BacktestTimeController instance;

    public static synchronized BacktestTimeController getInstance() {
        if (instance == null) instance = new BacktestTimeController();
        return instance;
    }

    private BacktestTimeController() {}

    // ── 상태 필드 ────────────────────────────────────
    private volatile boolean running        = false;
    private volatile boolean paused         = false;

    private String            userId;
    private long              sessionId;
    private LocalDateTime     startSimTime;
    private LocalDateTime     endSimTime;
    private LocalDateTime     currentSimTime;
    private BacktestSpeed     currentSpeed  = BacktestSpeed.SPEED_1X;

    /** 종료 1주일 전 알림을 이미 발송했는지 여부 (중복 방지) */
    private boolean finalWeekAlertSent = false;

    // ── 스케줄러 ──────────────────────────────────────
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?>        tickFuture;

    // ── DB 업데이트 주기 (매 30 tick = 30초마다 DB 반영) ──
    private static final int DB_FLUSH_INTERVAL = 30;
    private int tickCount = 0;

    private final BacktestSessionDAO sessionDAO = new BacktestSessionDAO();

    // ── 리스너 ───────────────────────────────────────
    private final List<BacktestTickListener>  tickListeners  = new ArrayList<>();
    private final List<BacktestEventListener> eventListeners = new ArrayList<>();

    // ════════════════════════════════════════════════
    //  리스너 인터페이스
    // ════════════════════════════════════════════════

    /**
     * 매 tick(1초)마다 호출됩니다.
     * Chart, MarketPanel 등이 이 인터페이스를 구현해 데이터를 갱신합니다.
     */
    public interface BacktestTickListener {
        void onTick(LocalDateTime currentSimTime, BacktestSpeed speed);
    }

    /**
     * 세션 수준의 중요 이벤트(종료 1주일 전 경보, 세션 종료 등)를 수신합니다.
     */
    public interface BacktestEventListener {
        /**
         * 세션 종료 1주일 전 도달 시 1회 호출됩니다.
         * 구현체는 "포지션 정리" 다이얼로그를 띄우면 됩니다.
         *
         * @param hasPositions 현재 포지션 보유 여부
         */
        void onFinalWeekWarning(boolean hasPositions);

        /**
         * 세션이 완전히 종료되었을 때 호출됩니다.
         */
        void onSessionEnded();

        /**
         * 배속이 강제로 변경되었을 때 호출됩니다.
         * (종료 1주일 전 구간에서 고배속 → 저배속 다운그레이드)
         */
        void onSpeedForced(BacktestSpeed newSpeed);
    }

    // ════════════════════════════════════════════════
    //  리스너 등록 / 해제
    // ════════════════════════════════════════════════

    public void addTickListener(BacktestTickListener l)   { tickListeners.add(l); }
    public void removeTickListener(BacktestTickListener l){ tickListeners.remove(l); }

    public void addEventListener(BacktestEventListener l)   { eventListeners.add(l); }
    public void removeEventListener(BacktestEventListener l){ eventListeners.remove(l); }

    // ════════════════════════════════════════════════
    //  세션 시작
    // ════════════════════════════════════════════════

    /**
     * 백테스팅 세션을 시작(재개)합니다.
     *
     * @param userId         사용자 ID
     * @param sessionId      세션 ID (simulation_sessions.session_id)
     * @param startSimTime   세션 시뮬레이션 시작 시각
     * @param currentSimTime 현재 진행 중인 시뮬레이션 시각 (재개 시 마지막 저장 시각)
     * @param endSimTime     세션 시뮬레이션 종료 시각 (start + 1month)
     */
    public synchronized void startSession(String userId, long sessionId,
                                          LocalDateTime startSimTime,
                                          LocalDateTime currentSimTime,
                                          LocalDateTime endSimTime) {
        // 이미 실행 중이면 정지 후 재시작
        if (running) stop();

        this.userId         = userId;
        this.sessionId      = sessionId;
        this.startSimTime   = startSimTime;
        this.endSimTime     = endSimTime;
        this.currentSimTime = currentSimTime;
        this.finalWeekAlertSent = false;
        this.tickCount      = 0;
        this.running        = true;
        this.paused         = false;

        // 기존 TimeController 의 실시간 WebSocket 종료
        UpbitWebSocketDao.getInstance().close();

        // 스케줄러 시작 (1초 주기)
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "BacktestTicker");
            t.setDaemon(true);
            return t;
        });
        tickFuture = scheduler.scheduleAtFixedRate(this::tick, 0, 1, TimeUnit.SECONDS);

        System.out.println("[BacktestTimeController] 세션 시작: " + currentSimTime + " ~ " + endSimTime);
    }

    // ════════════════════════════════════════════════
    //  Tick (핵심 루프)
    // ════════════════════════════════════════════════

    private void tick() {
        if (paused || !running) return;

        try {
            // 1. 종료 1주일 전 구간 진입 감지 및 배속 제한
            checkFinalWeekEntry();

            // 2. 시간 전진
            currentSimTime = currentSimTime.plusMinutes(currentSpeed.getMinutesPerTick());

            // 3. 세션 종료 판정
            if (!currentSimTime.isBefore(endSimTime)) {
                currentSimTime = endSimTime; // 오버슈트 방지
                fireTickListeners();
                endSession();
                return;
            }

            // 4. 리스너 브로드캐스트
            fireTickListeners();

            // 5. DB 비동기 저장 (매 N tick)
            if (++tickCount % DB_FLUSH_INTERVAL == 0) {
                final LocalDateTime snapshot = currentSimTime;
                final long sid = sessionId;
                // 별도 스레드로 분리해 tick을 블로킹하지 않음
                Thread.ofVirtual().start(() ->
                    sessionDAO.updateCurrentSimTime(sid, snapshot)
                );
            }

        } catch (Exception e) {
            System.err.println("[BacktestTimeController] tick 오류: " + e.getMessage());
        }
    }

    // ════════════════════════════════════════════════
    //  종료 1주일 전 감지
    // ════════════════════════════════════════════════

    private void checkFinalWeekEntry() {
        LocalDateTime finalWeekStart = endSimTime.minusWeeks(1);

        // 현재 시간이 종료 1주일 전 구간에 진입했는지 확인
        boolean inFinalWeek = !currentSimTime.isBefore(finalWeekStart);

        if (inFinalWeek) {
            // 배속 강제 제한
            if (!currentSpeed.isAllowedInFinalWeek()) {
                BacktestSpeed forcedSpeed = BacktestSpeed.SPEED_10X;
                currentSpeed = forcedSpeed;
                for (BacktestEventListener l : eventListeners) {
                    try { l.onSpeedForced(forcedSpeed); } catch (Exception e) { e.printStackTrace(); }
                }
                System.out.println("[BacktestTimeController] 종료 1주일 전 → 배속 강제: " + forcedSpeed);
            }

            // 포지션 정리 알림 (1회만)
            if (!finalWeekAlertSent) {
                finalWeekAlertSent = true;
                boolean hasPos = sessionDAO.hasOpenPositions(userId);
                for (BacktestEventListener l : eventListeners) {
                    try { l.onFinalWeekWarning(hasPos); } catch (Exception e) { e.printStackTrace(); }
                }
                System.out.println("[BacktestTimeController] 종료 1주일 전 경보 발령 (포지션 보유: " + hasPos + ")");
            }
        }
    }

    // ════════════════════════════════════════════════
    //  세션 종료
    // ════════════════════════════════════════════════

    private void endSession() {
        running = false;
        if (tickFuture != null) tickFuture.cancel(false);
        if (scheduler  != null) scheduler.shutdown();

        // DB 최종 저장
        sessionDAO.updateCurrentSimTime(sessionId, currentSimTime);
        sessionDAO.deactivateSession(sessionId);

        for (BacktestEventListener l : eventListeners) {
            try { l.onSessionEnded(); } catch (Exception e) { e.printStackTrace(); }
        }
        System.out.println("[BacktestTimeController] 세션 종료: " + currentSimTime);
    }

    // ════════════════════════════════════════════════
    //  공개 제어 메서드
    // ════════════════════════════════════════════════

    /** 일시 정지 */
    public void pause() {
        paused = true;
        System.out.println("[BacktestTimeController] 일시 정지");
        
        // 🚀 [추가된 코드] 일시정지 버튼을 누를 때 즉시 DB에 진행 시간 저장
        if (currentSimTime != null) {
            final LocalDateTime snapshot = currentSimTime;
            final long sid = sessionId;
            Thread.ofVirtual().start(() -> 
                sessionDAO.updateCurrentSimTime(sid, snapshot)
            );
        }
    }

    /** 재개 */
    public void resume() {
        paused = false;
        System.out.println("[BacktestTimeController] 재개");
    }

    /**
     * 배속 변경
     * 종료 1주일 전 구간이면 허용된 배속만 적용됩니다.
     *
     * @param speed 요청 배속
     * @return 실제 적용된 배속
     */
    public BacktestSpeed setSpeed(BacktestSpeed speed) {
        boolean inFinalWeek = !currentSimTime.isBefore(endSimTime.minusWeeks(1));

        if (inFinalWeek && !speed.isAllowedInFinalWeek()) {
            // 강제 다운그레이드
            currentSpeed = BacktestSpeed.SPEED_10X;
            System.out.println("[BacktestTimeController] 종료 1주일 전 구간 — 배속 " + speed + " 불허, " + currentSpeed + " 적용");
        } else {
            currentSpeed = speed;
        }
        return currentSpeed;
    }

    /** 실행 중 여부 */
    public void stop() {
        running = false;
        paused  = false;
        if (tickFuture != null) tickFuture.cancel(true);
        if (scheduler  != null) scheduler.shutdownNow();
        
        // 🚀 [추가된 코드] 백테스팅이 중단될 때 강제로 즉시 DB에 진행 시간 저장
        // 스레드 풀이 강제 종료되므로 비동기가 아닌 동기(Synchronous) 방식으로 즉시 실행하여 안전하게 기록
        if (currentSimTime != null && sessionId > 0) {
            sessionDAO.updateCurrentSimTime(sessionId, currentSimTime);
        }
    }

    // ════════════════════════════════════════════════
    //  내부 유틸
    // ════════════════════════════════════════════════

    private void fireTickListeners() {
        final LocalDateTime snapTime  = currentSimTime;
        final BacktestSpeed snapSpeed = currentSpeed;
        for (BacktestTickListener l : tickListeners) {
            try { l.onTick(snapTime, snapSpeed); } catch (Exception e) { e.printStackTrace(); }
        }
    }

    // ════════════════════════════════════════════════
    //  Getter
    // ════════════════════════════════════════════════

    public boolean         isRunning()        { return running; }
    public boolean         isPaused()         { return paused; }
    public LocalDateTime   getCurrentSimTime(){ return currentSimTime; }
    public LocalDateTime   getEndSimTime()    { return endSimTime; }
    public BacktestSpeed   getCurrentSpeed()  { return currentSpeed; }
    public long            getSessionId()     { return sessionId; }

    /** 전체 세션 진행률 (0.0 ~ 1.0) */
    public double getProgress() {
        if (startSimTime == null || endSimTime == null) return 0.0;
        long total   = ChronoUnit.MINUTES.between(startSimTime, endSimTime);
        long elapsed = ChronoUnit.MINUTES.between(startSimTime, currentSimTime);
        if (total == 0) return 1.0;
        return Math.max(0.0, Math.min(1.0, (double) elapsed / total));
    }

    /** 종료까지 남은 시뮬레이션 시간 (분) */
    public long getRemainingMinutes() {
        if (currentSimTime == null || endSimTime == null) return 0;
        return ChronoUnit.MINUTES.between(currentSimTime, endSimTime);
    }

    /** 종료 1주일 전 구간 진입 여부 */
    public boolean isInFinalWeek() {
        if (currentSimTime == null || endSimTime == null) return false;
        return !currentSimTime.isBefore(endSimTime.minusWeeks(1));
    }
}