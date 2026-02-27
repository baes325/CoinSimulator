package com.team.coin_simulator.chart;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import org.jfree.chart.*;
import org.jfree.chart.axis.*;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;

/**
 * [CandleChartPanel]
 * JFreeChart를 이용한 암호화폐 캔들 차트 컴포넌트.
 *
 * ── 주요 기능 ──────────────────────────────────────────
 *  1. 현재가 점선 + Y축 영역 위 가격 박스 표시
 *  2. 초기 로딩 시 최근 N개 캔들만 표시, 가격 범위 자동 맞춤
 *  3. 실시간 라이브 캔들 업데이트 (Swing Timer 기반)
 *  4. 백테스팅 모드: targetTime 기준 과거 데이터만 표시
 *  5. ★ 모든 타임프레임 뷰포트 페이징
 *     - 드래그/줌 시 화면 범위 + 버퍼(PAGE_BUFFER)만큼만 DB 조회
 *     - 캐시 범위의 25% 이내 접근 시 백그라운드 pre-fetch
 *     - TF_4H: TF_1H 데이터를 페이징 후 리샘플링
 *     - TF_1MON DB 없음: TF_1D 페이징 후 월봉 리샘플링
 * ────────────────────────────────────────────────────────
 */
public class CandleChartPanel extends JPanel {

    // ── 타임프레임 정의 (분 단위) ────────────────────────
    private static final int TF_1M   = 1;
    private static final int TF_30M  = 30;
    private static final int TF_1H   = 60;
    private static final int TF_4H   = 240;
    private static final int TF_1D   = 1440;
    private static final int TF_1MON = 43200; // 30일 기준

    /** 타임프레임별 화면 기본 표시 캔들 수 */
    private static final int DISPLAY_1M   = 55;
    private static final int DISPLAY_30M  = 55;
    private static final int DISPLAY_1H   = 55;
    private static final int DISPLAY_4H   = 55;
    private static final int DISPLAY_1D   = 55;
    private static final int DISPLAY_1MON = 55;

    // ── ★ 뷰포트 페이징 설정 ────────────────────────────
    /**
     * 뷰포트 양쪽으로 미리 로드할 버퍼 캔들 수.
     * 화면에 55개가 보이면 실제 캐시는 최대 55 + PAGE_BUFFER*2 개.
     */
    private static final int PAGE_BUFFER = 300;

    /**
     * 캐시의 몇 % 이내로 뷰포트가 접근하면 pre-fetch 시작.
     * 0.25 = 앞/뒤 25% 이내 진입 시.
     */
    private static final double PREFETCH_THRESHOLD = 0.25;

    // ── ★ 줌 한도 설정 ──────────────────────────────────
    /** 줌 인 최소 캔들 수 — 이보다 더 확대 불가 */
    private static final int MIN_VISIBLE_CANDLES = 5;

    /**
     * 줌 아웃 최대 캔들 수 — 이보다 더 축소 불가.
     * 1분봉 200개 = 약 3.3시간, 1일봉 200개 = 약 200일.
     */
    private static final int MAX_VISIBLE_CANDLES = 200;

    // ── 타임프레임별 페이지 캐시 ─────────────────────────
    /**
     * 타임프레임 하나의 캐시 상태를 담는 내부 클래스.
     * TF_4H는 TF_1H 데이터를 캐시하므로 cacheMap 키는 TF_1H로 저장.
     */
    private static class PageCache {
        List<CandleDTO> candles = new ArrayList<>(); // ASC 정렬
        long fromMs = -1;
        long toMs   = -1;
        volatile boolean prefetchInProgress = false;

        void reset() {
            candles = new ArrayList<>();
            fromMs = -1;
            toMs   = -1;
            prefetchInProgress = false;
        }

        boolean isInitialized() { return fromMs >= 0 && toMs >= 0; }
    }

    /**
     * DB 조회 unit → PageCache 맵.
     * TF_4H 버튼은 TF_1H 캐시를 공유합니다.
     */
    private final Map<Integer, PageCache> cacheMap = new HashMap<>();

    // ── 차트 컴포넌트 ────────────────────────────────────
    private JFreeChart chart;
    private XYPlot plot;
    private OverlayChartPanel chartPanel;
    private CandleDAO candleDAO = new CandleDAO();
    private JButton selectedButton;

    // ── 초기 상태 ────────────────────────────────────────
    private String currentMarket = "KRW-BTC";
    private int currentTimeframe = TF_1M;
    private Point lastMousePoint;

    // ── 백테스팅 상태 ─────────────────────────────────────
    private LocalDateTime backtestTargetTime = null;

    // ── 실시간 라이브 캔들 ────────────────────────────────
    private CandleDTO liveCandle = null;
    private LocalDateTime liveCandleMinuteStart = null;

    // ── 실시간 타이머 ────────────────────────────────────
    private Timer liveTimer;
    private static final int LIVE_RENDER_MS = 500;
    private volatile double latestLivePrice = -1;
    private volatile long latestLiveTimestamp = -1;

    // ── 웹소켓 ───────────────────────────────────────────
    private UpbitWebSocket webSocketClient;

    // ── 현재가 박스 표시 ──────────────────────────────────
    private double overlayPrice = Double.NaN;
    private boolean overlayRising = true;


    // ════════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════════
    public CandleChartPanel(String title) {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        initCacheMap();

        // plot이 null인 상태에서 createDataset 호출 — fetchPagedCandles 내부에서 처리
        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, null);
        chart = ChartFactory.createCandlestickChart(null, "", "", dataset, false);
        chart.setTitle(getCurrentMarketSymbol());
        plot = (XYPlot) chart.getPlot(); // ← 여기서 plot 초기화

        configureChartUI();
        setupChartPanel();
        createButtonPanel();

        add(chartPanel, BorderLayout.CENTER);

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        drawCurrentPriceDashLine(dataset);

        startLiveTimer();
        connectWebSocket();
    }

    private void initCacheMap() {
        // TF_4H는 TF_1H를 공유하므로 별도 엔트리 불필요
        for (int tf : new int[]{TF_1M, TF_30M, TF_1H, TF_1D, TF_1MON}) {
            cacheMap.put(tf, new PageCache());
        }
    }


    // ════════════════════════════════════════════════════
    //  차트 UI 설정
    // ════════════════════════════════════════════════════

    private void configureChartUI() {
        plot.setRangeAxisLocation(AxisLocation.TOP_OR_RIGHT);
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setAutoRangeIncludesZero(false);
        yAxis.setNumberFormatOverride(buildPriceFormat());

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM/dd HH:mm"));
        domainAxis.setTickLabelFont(new Font("Nanum Gothic", Font.PLAIN, 11));
        domainAxis.setAutoTickUnitSelection(true);

        CandlestickRenderer renderer = new CandlestickRenderer();
        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST);
        renderer.setAutoWidthGap(0.0);
        renderer.setDrawVolume(false);
        renderer.setUpPaint(Color.RED);
        renderer.setDownPaint(Color.BLUE);
        plot.setRenderer(renderer);
    }

    private void setupChartPanel() {
        chartPanel = new OverlayChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(900, 500));
        chartPanel.setRangeZoomable(false);
        chartPanel.setDomainZoomable(false);
        chartPanel.setMouseWheelEnabled(false);
        plot.setDomainPannable(true);
        plot.setRangePannable(true);

        chartPanel.addMouseWheelListener(e -> {
            handleFixedRightZoom(e.getWheelRotation());
            SwingUtilities.invokeLater(() -> chartPanel.repaint());
        });

        chartPanel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { lastMousePoint = e.getPoint(); }
            public void mouseReleased(MouseEvent e) { lastMousePoint = null; chartPanel.repaint(); }
        });

        chartPanel.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (lastMousePoint == null) return;

                int dx = e.getX() - lastMousePoint.x;
                lastMousePoint = e.getPoint();

                DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
                Rectangle2D plotArea = chartPanel.getScreenDataArea();
                if (plotArea == null || plotArea.getWidth() == 0) return;

                double msPerPixel = domainAxis.getRange().getLength() / plotArea.getWidth();
                double shift = -dx * msPerPixel;

                domainAxis.setRange(
                    domainAxis.getRange().getLowerBound() + shift,
                    domainAxis.getRange().getUpperBound() + shift
                );

                updateYAxisRange((OHLCDataset) plot.getDataset());

                // ★ 드래그 시 현재 타임프레임 캐시 경계 근접 여부 확인
                checkAndPrefetchIfNeeded(currentTimeframe);

                chartPanel.repaint();
            }
        });
    }

    private void createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.setBackground(Color.WHITE);

        String[] labels     = {"1분",  "30분",  "1시간", "4시간", "1일",   "1달"};
        int[]    timeframes = {TF_1M, TF_30M, TF_1H,  TF_4H,  TF_1D, TF_1MON};

        for (int i = 0; i < labels.length; i++) {
            final int timeframe = timeframes[i];

            JButton button = new JButton(labels[i]);
            button.setBackground(Color.LIGHT_GRAY);
            button.setFocusPainted(false);

            if (timeframe == currentTimeframe) {
                button.setBackground(Color.ORANGE);
                selectedButton = button;
            }

            button.addActionListener(e -> {
                if (selectedButton != null) selectedButton.setBackground(Color.LIGHT_GRAY);
                button.setBackground(Color.ORANGE);
                selectedButton = button;
                currentTimeframe = timeframe;

                // 타임프레임 전환 시 해당 캐시만 초기화 (다른 TF 캐시는 유지)
                resetCache(dbUnitOf(timeframe));
                refreshChart();
            });

            buttonPanel.add(button);
        }
        add(buttonPanel, BorderLayout.NORTH);
    }


    // ════════════════════════════════════════════════════
    //  ★ 뷰포트 페이징 — 전 타임프레임 공통
    // ════════════════════════════════════════════════════

    /**
     * TF_4H는 DB에서 TF_1H로 조회하므로 캐시 키(DB unit)를 TF_1H로 변환.
     * 나머지 타임프레임은 그대로.
     */
    private int dbUnitOf(int timeframe) {
        return (timeframe == TF_4H) ? TF_1H : timeframe;
    }

    /**
     * 리샘플링 비율: DB 조회 캔들 몇 개가 화면 캔들 1개로 합쳐지는지.
     *
     * TF_4H → 1H 데이터 4개 → 4H 캔들 1개  → 비율 4
     * 나머지 → 1:1                           → 비율 1
     *
     * bufferMs에 이 비율을 곱해서 리샘플링 후에도
     * MAX_VISIBLE_CANDLES 개를 충분히 채울 수 있게 보정합니다.
     */
    private int resampleRatioOf(int timeframe) {
        return (timeframe == TF_4H) ? (TF_4H / TF_1H) : 1;
    }

    /** 전체 캐시 초기화 (종목/모드 변경 시) */
    private void resetAllCaches() {
        for (PageCache c : cacheMap.values()) c.reset();
    }

    /** 특정 DB unit 캐시만 초기화 */
    private void resetCache(int dbUnit) {
        PageCache c = cacheMap.get(dbUnit);
        if (c != null) c.reset();
    }

    /**
     * 뷰포트 기반 캔들 페이징 조회 (모든 타임프레임 공통 진입점).
     *
     * ── 분기 로직 ─────────────────────────────────────────────────
     *  1) plot==null(생성자 첫 호출) or 캐시 미초기화
     *     → targetTime 또는 현재 시각 기준 PAGE_BUFFER*2 구간 최초 로드
     *  2) 뷰포트가 캐시 범위 안 → 캐시 반환 (DB 조회 없음)
     *  3) 뷰포트가 캐시 밖으로 점프 → 뷰포트 중심 기준 동기 재조회
     * ──────────────────────────────────────────────────────────────
     *
     * @param dbUnit     실제 DB 조회 unit (TF_4H 시 TF_1H 전달)
     * @param targetTime 백테스팅 기준시각 (실시간이면 null)
     */
    private List<CandleDTO> fetchPagedCandles(String market, int dbUnit,
                                               LocalDateTime targetTime) {
        PageCache cache   = cacheMap.get(dbUnit);
        // ★ 리샘플링 비율 반영: TF_4H는 1H 데이터를 4개씩 합치므로
        //   버퍼를 4배 확보해야 리샘플링 후에도 MAX_VISIBLE_CANDLES 개를 채울 수 있음
        long bufferMs     = (long)(PAGE_BUFFER * resampleRatioOf(currentTimeframe) * getCandleIntervalMs(dbUnit));

        // ── 첫 로드 ──
        if (plot == null || !cache.isInitialized()) {
            long fetchTo   = (targetTime != null)
                    ? java.sql.Timestamp.valueOf(targetTime).getTime()
                    : System.currentTimeMillis();
            long fetchFrom = fetchTo - bufferMs * 2;
            return doFetchAndCache(cache, market, dbUnit, fetchFrom, fetchTo, targetTime);
        }

        DateAxis axis    = (DateAxis) plot.getDomainAxis();
        double axisLower = axis.getRange().getLowerBound();
        double axisUpper = axis.getRange().getUpperBound();

        // ── 뷰포트 점프 → 동기 재조회 ──
        if (axisUpper < cache.fromMs || axisLower > cache.toMs) {
            long center    = (long)((axisLower + axisUpper) / 2);
            long fetchFrom = center - bufferMs;
            long fetchTo   = center + bufferMs;
            System.out.printf("[Paging] unit=%d 점프 → 동기 재조회%n", dbUnit);
            return doFetchAndCache(cache, market, dbUnit, fetchFrom, fetchTo, targetTime);
        }

        // ── 캐시 적중 ──
        return cache.candles;
    }

    /**
     * DB 범위 조회 후 캐시 갱신.
     */
    private List<CandleDTO> doFetchAndCache(PageCache cache, String market, int dbUnit,
                                             long fetchFrom, long fetchTo,
                                             LocalDateTime targetTime) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDateTime from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fetchFrom), kst);
        LocalDateTime to   = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fetchTo),   kst);

        List<CandleDTO> result;
        if (targetTime != null) {
            result = candleDAO.getCandlesInRangeHistorical(market, dbUnit, from, to, targetTime);
        } else {
            result = candleDAO.getCandlesInRange(market, dbUnit, from, to);
        }

        // getCandlesInRange는 ASC 반환 보장
        cache.candles = result;
        cache.fromMs  = fetchFrom;
        cache.toMs    = fetchTo;
        System.out.printf("[Paging] unit=%d 조회: %s ~ %s → %d개%n", dbUnit, from, to, result.size());
        return result;
    }

    /**
     * 드래그/줌 후 캐시 경계 PREFETCH_THRESHOLD 이내 접근 시
     * 백그라운드에서 새 범위를 미리 조회합니다.
     */
    private void checkAndPrefetchIfNeeded(int timeframe) {
        int dbUnit      = dbUnitOf(timeframe);
        PageCache cache = cacheMap.get(dbUnit);
        if (!cache.isInitialized() || cache.prefetchInProgress) return;

        DateAxis axis = (DateAxis) plot.getDomainAxis();
        double lower  = axis.getRange().getLowerBound();
        double upper  = axis.getRange().getUpperBound();
        long cacheLen = cache.toMs - cache.fromMs;

        boolean nearLeft  = lower < cache.fromMs + cacheLen * PREFETCH_THRESHOLD;
        boolean nearRight = upper > cache.toMs   - cacheLen * PREFETCH_THRESHOLD;
        if (!nearLeft && !nearRight) return;

        cache.prefetchInProgress = true;

        long center   = (long)((lower + upper) / 2);
        // ★ 리샘플링 비율 반영: TF_4H는 4배 버퍼 필요
        long bufferMs = (long)(PAGE_BUFFER * resampleRatioOf(timeframe) * getCandleIntervalMs(dbUnit));
        long newFrom  = center - bufferMs;
        long newTo    = center + bufferMs;

        final String        mkt = currentMarket;
        final LocalDateTime tgt = backtestTargetTime;
        final int           tf  = timeframe;

        new Thread(() -> {
            ZoneId kst = ZoneId.of("Asia/Seoul");
            LocalDateTime from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(newFrom), kst);
            LocalDateTime to   = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(newTo),   kst);

            List<CandleDTO> fresh;
            if (tgt != null) {
                fresh = candleDAO.getCandlesInRangeHistorical(mkt, dbUnit, from, to, tgt);
            } else {
                fresh = candleDAO.getCandlesInRange(mkt, dbUnit, from, to);
            }
            System.out.printf("[Paging] unit=%d pre-fetch: %s ~ %s → %d개%n",
                    dbUnit, from, to, fresh.size());

            SwingUtilities.invokeLater(() -> {
                cache.candles = fresh;
                cache.fromMs  = newFrom;
                cache.toMs    = newTo;
                cache.prefetchInProgress = false;

                // 현재 보고 있는 타임프레임일 때만 dataset 갱신
                if (currentTimeframe == tf) {
                    OHLCDataset ds = assembleDataset(mkt, tf, fresh, tgt);
                    plot.setDataset(ds);
                    updateYAxisRange(ds);
                    chartPanel.repaint();
                }
            });
        }, "Prefetch-u" + dbUnit).start();
    }

    /**
     * pre-fetch 완료 후 타임프레임에 맞게 dataset 조립.
     * (4H 리샘플링, liveCandle 병합 포함)
     */
    private OHLCDataset assembleDataset(String market, int timeframe,
                                         List<CandleDTO> raw, LocalDateTime targetTime) {
        List<CandleDTO> processed = applyResample(timeframe, raw);

        if (targetTime == null && liveCandle != null) {
            return mergeWithLiveCandle(market, timeframe, new ArrayList<>(processed));
        }
        return processed.isEmpty() ? emptyDataset(market) : buildDataset(market, processed);
    }

    /**
     * raw 리스트에 타임프레임별 리샘플링을 적용합니다.
     * TF_4H: resample4H, TF_1MON: resampleByMonth, 나머지: 그대로.
     */
    private List<CandleDTO> applyResample(int timeframe, List<CandleDTO> raw) {
        if (timeframe == TF_4H)   return resample4H(raw);
        if (timeframe == TF_1MON) return resampleByMonth(raw);
        return raw;
    }


    // ════════════════════════════════════════════════════
    //  통합 데이터셋 생성
    // ════════════════════════════════════════════════════

    /**
     * 실시간/백테스팅 모드에 따라 해당 타임프레임 캔들을 조회합니다.
     *
     * ── 조회 전략 ──────────────────────────────────────────────────
     *  TF_4H  → TF_1H 페이징 후 4H 리샘플링
     *  TF_1MON → TF_1MON 페이징 (없으면 TF_1D 페이징 후 월봉 리샘플링)
     *  나머지 → 해당 unit 페이징 (없으면 TF_1M 페이징 후 리샘플링)
     * ──────────────────────────────────────────────────────────────
     */
    private OHLCDataset createDataset(String market, int timeframe, LocalDateTime targetTime) {
        int dbUnit = dbUnitOf(timeframe);
        List<CandleDTO> raw = fetchPagedCandles(market, dbUnit, targetTime);

        if (raw.isEmpty()) {
            // DB에 해당 unit 데이터 없음 → 폴백
            return fallbackDataset(market, timeframe, targetTime);
        }

        return buildDataset(market, applyResample(timeframe, raw));
    }

    /**
     * DB에 해당 unit 데이터가 없을 때의 폴백 처리.
     *
     * TF_1MON → TF_1D 페이징 후 월봉 리샘플링
     * 나머지  → TF_1M 페이징 후 해당 TF 리샘플링
     */
    private OHLCDataset fallbackDataset(String market, int timeframe, LocalDateTime targetTime) {
        System.out.println("[CandleChart] unit=" + timeframe + " 데이터 없음 → 폴백");

        List<CandleDTO> base;
        if (timeframe == TF_1MON) {
            // 일봉으로 월봉 리샘플링
            base = fetchPagedCandles(market, TF_1D, targetTime);
            if (!base.isEmpty()) return buildDataset(market, resampleByMonth(base));
            // 일봉도 없으면 1분봉 폴백
            base = fetchPagedCandles(market, TF_1M, targetTime);
        } else {
            base = fetchPagedCandles(market, TF_1M, targetTime);
        }

        if (base.isEmpty()) return emptyDataset(market);
        return buildDataset(market, resampleByTimeframe(base, timeframe));
    }

    /**
     * 실시간 모드 전용: 캐시 재사용 + liveCandle 병합.
     * 500ms 라이브 틱마다 호출. DB 재조회 없음.
     */
    private OHLCDataset createDatasetWithLiveCandle(String market, int timeframe) {
        int dbUnit = dbUnitOf(timeframe);
        List<CandleDTO> raw = new ArrayList<>(cacheMap.get(dbUnit).candles);
        List<CandleDTO> processed = applyResample(timeframe, raw);

        if (liveCandle == null) {
            return processed.isEmpty() ? emptyDataset(market) : buildDataset(market, processed);
        }
        return mergeWithLiveCandle(market, timeframe, new ArrayList<>(processed));
    }

    /**
     * liveCandle을 processed 리스트에 병합 후 dataset 반환.
     *
     * ── 병합 규칙 ──────────────────────────────────────────────────
     *  TF_1M:    같은 분봉이면 대체, 아니면 추가
     *  상위 TF:  같은 블록이면 종가/고/저 갱신, 새 블록이면 추가
     * ──────────────────────────────────────────────────────────────
     */
    private OHLCDataset mergeWithLiveCandle(String market, int timeframe,
                                             List<CandleDTO> processed) {
        if (processed.isEmpty()) {
            processed.add(liveCandle);
            return buildDataset(market, processed);
        }

        CandleDTO last = processed.get(processed.size() - 1);

        if (timeframe == TF_1M) {
            if (liveCandleMinuteStart != null) {
                LocalDateTime lastMinute = last.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES);
                if (lastMinute.equals(liveCandleMinuteStart)) {
                    processed.remove(processed.size() - 1);
                }
            }
            processed.add(liveCandle);
        } else {
            String lastBlock = calcBlockKey(last.getCandleDateTimeKst(), timeframe);
            String liveBlock = calcBlockKey(liveCandle.getCandleDateTimeKst(), timeframe);

            if (lastBlock.equals(liveBlock)) {
                CandleDTO merged = copyCandle(last);
                merged.setTradePrice(liveCandle.getTradePrice());
                merged.setHighPrice(Math.max(last.getHighPrice(), liveCandle.getHighPrice()));
                merged.setLowPrice(Math.min(last.getLowPrice(), liveCandle.getLowPrice()));
                processed.set(processed.size() - 1, merged);
            } else {
                CandleDTO newBlock = copyCandle(liveCandle);
                newBlock.setUnit(timeframe);
                processed.add(newBlock);
            }
        }

        return buildDataset(market, processed);
    }


    // ════════════════════════════════════════════════════
    //  리샘플링
    // ════════════════════════════════════════════════════

    private List<CandleDTO> resampleByTimeframe(List<CandleDTO> list, int timeframe) {
        if (timeframe == TF_4H)   return resample4H(list);
        if (timeframe == TF_1MON) return resampleByMonth(list);
        return resampleFixedMinutes(list, timeframe);
    }

    private List<CandleDTO> resampleFixedMinutes(List<CandleDTO> src, int blockMinutes) {
        List<CandleDTO> result   = new ArrayList<>();
        List<CandleDTO> group    = new ArrayList<>();
        String          groupKey = null;

        for (CandleDTO c : src) {
            String key = calcBlockKey(c.getCandleDateTimeKst(), blockMinutes);
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(mergeGroup(group, blockMinutes));
                group.clear();
                groupKey = key;
            }
            group.add(c);
        }
        if (!group.isEmpty()) result.add(mergeGroup(group, blockMinutes));
        return result;
    }

    private List<CandleDTO> resampleByMonth(List<CandleDTO> src) {
        List<CandleDTO> result   = new ArrayList<>();
        List<CandleDTO> group    = new ArrayList<>();
        String          groupKey = null;

        for (CandleDTO c : src) {
            String key = c.getCandleDateTimeKst().getYear() + "-"
                       + String.format("%02d", c.getCandleDateTimeKst().getMonthValue());
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(mergeGroup(group, TF_1MON));
                group.clear();
                groupKey = key;
            }
            group.add(c);
        }
        if (!group.isEmpty()) result.add(mergeGroup(group, TF_1MON));
        return result;
    }

    private CandleDTO mergeGroup(List<CandleDTO> group, int blockMinutes) {
        CandleDTO first = group.get(0);
        CandleDTO last  = group.get(group.size() - 1);

        LocalDateTime kst = first.getCandleDateTimeKst();
        LocalDateTime blockStart;
        if (blockMinutes == TF_1MON) {
            blockStart = kst.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        } else {
            LocalDateTime KST_EPOCH = LocalDateTime.of(1970, 1, 1, 9, 0);
            long epochMinutes = ChronoUnit.MINUTES.between(KST_EPOCH, kst);
            long blockIdx     = (epochMinutes / blockMinutes) * blockMinutes;
            blockStart = KST_EPOCH.plusMinutes(blockIdx);
        }

        CandleDTO m = new CandleDTO();
        m.setMarket(first.getMarket());
        m.setCandleDateTimeKst(blockStart);
        m.setCandleDateTimeUtc(blockStart.minusHours(9));
        m.setOpeningPrice(first.getOpeningPrice());
        m.setTradePrice(last.getTradePrice());
        m.setHighPrice(group.stream().mapToDouble(CandleDTO::getHighPrice).max().orElse(0));
        m.setLowPrice(group.stream().mapToDouble(CandleDTO::getLowPrice).min().orElse(0));
        m.setCandleAccTradeVolume(group.stream().mapToDouble(CandleDTO::getCandleAccTradeVolume).sum());
        m.setUnit(blockMinutes);
        return m;
    }

    private List<CandleDTO> resample4H(List<CandleDTO> hourList) {
        List<CandleDTO> result = new ArrayList<>();
        List<CandleDTO> group  = new ArrayList<>();
        String groupKey = null;

        for (CandleDTO candle : hourList) {
            String key = calcBlockKey(candle.getCandleDateTimeKst(), TF_4H);
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(merge4HGroup(group));
                group.clear();
                groupKey = key;
            }
            group.add(candle);
        }
        if (!group.isEmpty()) result.add(merge4HGroup(group));
        return result;
    }

    private CandleDTO merge4HGroup(List<CandleDTO> group) {
        CandleDTO first = group.get(0);
        CandleDTO last  = group.get(group.size() - 1);

        LocalDateTime kst = first.getCandleDateTimeKst();
        LocalDateTime KST_EPOCH = LocalDateTime.of(1970, 1, 1, 9, 0);
        long epochMinutes = ChronoUnit.MINUTES.between(KST_EPOCH, kst);
        long blockStart   = (epochMinutes / TF_4H) * TF_4H;
        LocalDateTime blockStartTime = KST_EPOCH.plusMinutes(blockStart);

        CandleDTO m = new CandleDTO();
        m.setMarket(first.getMarket());
        m.setCandleDateTimeKst(blockStartTime);
        m.setCandleDateTimeUtc(blockStartTime.minusHours(9));
        m.setOpeningPrice(first.getOpeningPrice());
        m.setTradePrice(last.getTradePrice());
        m.setHighPrice(group.stream().mapToDouble(CandleDTO::getHighPrice).max().orElse(0));
        m.setLowPrice(group.stream().mapToDouble(CandleDTO::getLowPrice).min().orElse(0));
        m.setCandleAccTradeVolume(group.stream().mapToDouble(CandleDTO::getCandleAccTradeVolume).sum());
        m.setUnit(TF_4H);
        return m;
    }


    // ════════════════════════════════════════════════════
    //  데이터셋 / DTO 유틸리티
    // ════════════════════════════════════════════════════

    private OHLCDataset buildDataset(String market, List<CandleDTO> list) {
        int count = list.size();
        Date[]   date   = new Date[count];
        double[] high   = new double[count], low    = new double[count];
        double[] open   = new double[count], close  = new double[count];
        double[] volume = new double[count];

        for (int i = 0; i < count; i++) {
            CandleDTO dto = list.get(i);
            date[i]   = java.sql.Timestamp.valueOf(dto.getCandleDateTimeKst());
            open[i]   = dto.getOpeningPrice();
            high[i]   = dto.getHighPrice();
            low[i]    = dto.getLowPrice();
            close[i]  = dto.getTradePrice();
            volume[i] = dto.getCandleAccTradeVolume();
        }
        return new DefaultHighLowDataset(market, date, high, low, open, close, volume);
    }

    private CandleDTO copyCandle(CandleDTO src) {
        CandleDTO c = new CandleDTO();
        c.setMarket(src.getMarket());
        c.setCandleDateTimeKst(src.getCandleDateTimeKst());
        c.setCandleDateTimeUtc(src.getCandleDateTimeUtc());
        c.setOpeningPrice(src.getOpeningPrice());
        c.setHighPrice(src.getHighPrice());
        c.setLowPrice(src.getLowPrice());
        c.setTradePrice(src.getTradePrice());
        c.setTimestamp(src.getTimestamp());
        c.setCandleAccTradePrice(src.getCandleAccTradePrice());
        c.setCandleAccTradeVolume(src.getCandleAccTradeVolume());
        c.setUnit(src.getUnit());
        return c;
    }

    private OHLCDataset emptyDataset(String market) {
        return new DefaultHighLowDataset(market,
                new Date[0], new double[0], new double[0],
                new double[0], new double[0], new double[0]);
    }

    private String calcBlockKey(LocalDateTime kst, int timeframeMinutes) {
        if (timeframeMinutes == TF_1MON) {
            return kst.getYear() + "-" + String.format("%02d", kst.getMonthValue());
        }
        long epochMinutes = ChronoUnit.MINUTES.between(LocalDateTime.of(1970, 1, 1, 9, 0), kst);
        return Long.toString(epochMinutes / timeframeMinutes);
    }


    // ════════════════════════════════════════════════════
    //  차트 갱신
    // ════════════════════════════════════════════════════

    private void refreshChart() {
        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, backtestTargetTime);
        plot.setDataset(dataset);

        chart.setTitle(backtestTargetTime != null
                ? getCurrentMarketSymbol() + " (Backtesting)"
                : getCurrentMarketSymbol());

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        drawCurrentPriceDashLine(dataset);
    }

    private void applyDateAxisFormat() {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        String fmt;
        if (currentTimeframe >= TF_1MON)   fmt = "yyyy/MM";
        else if (currentTimeframe >= TF_1D) fmt = "MM/dd";
        else                                fmt = "MM/dd HH:mm";
        domainAxis.setDateFormatOverride(new SimpleDateFormat(fmt));
    }

    private void updateXAxisRange(OHLCDataset dataset) {
        int itemCount = dataset.getItemCount(0);
        if (itemCount <= 0) return;

        int displayCount = Math.min(itemCount, getDisplayCount(currentTimeframe));

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double lastTick  = dataset.getXValue(0, itemCount - 1);
        double firstTick = dataset.getXValue(0, itemCount - displayCount);
        double candleIntervalMs = getCandleIntervalMs(currentTimeframe);

        // 오른쪽 여백: 월봉은 1칸(다음 달 시작까지), 나머지는 라이브캔들 공간 4.5칸
        double rightPad = (currentTimeframe == TF_1MON) ? 1.0 : 4.5;
        domainAxis.setRange(
            firstTick  - candleIntervalMs * 0.5,
            lastTick   + candleIntervalMs * rightPad
        );
    }

    private double getCandleIntervalMs(int timeframeMinutes) {
        long minutes = (timeframeMinutes == TF_1MON) ? 30L * 24 * 60 : timeframeMinutes;
        return minutes * 60_000.0;
    }

    private int getDisplayCount(int tf) {
        if (tf == TF_1M)   return DISPLAY_1M;
        if (tf == TF_30M)  return DISPLAY_30M;
        if (tf == TF_1H)   return DISPLAY_1H;
        if (tf == TF_4H)   return DISPLAY_4H;
        if (tf == TF_1D)   return DISPLAY_1D;
        if (tf == TF_1MON) return DISPLAY_1MON;
        return 55;
    }

    private void updateYAxisRange(OHLCDataset dataset) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double visibleStart = domainAxis.getRange().getLowerBound();
        double visibleEnd   = domainAxis.getRange().getUpperBound();

        double maxHigh = -Double.MAX_VALUE;
        double minLow  =  Double.MAX_VALUE;
        boolean found  = false;

        for (int i = 0; i < dataset.getItemCount(0); i++) {
            double x = dataset.getXValue(0, i);
            if (x >= visibleStart && x <= visibleEnd) {
                maxHigh = Math.max(maxHigh, dataset.getHighValue(0, i));
                minLow  = Math.min(minLow,  dataset.getLowValue(0, i));
                found   = true;
            }
        }

        if (found) {
            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double margin = (maxHigh - minLow) * 0.05;
            yAxis.setRange(minLow - margin, maxHigh + margin);
            yAxis.setNumberFormatOverride(buildPriceFormat());
        }
    }

    private void drawCurrentPriceDashLine(OHLCDataset dataset) {
        plot.clearRangeMarkers();
        int itemCount = dataset.getItemCount(0);
        if (itemCount <= 0) return;

        double currentPrice  = dataset.getCloseValue(0, itemCount - 1);
        double previousPrice = (itemCount > 1) ? dataset.getCloseValue(0, itemCount - 2) : currentPrice;
        overlayRising = (currentPrice >= previousPrice);
        overlayPrice  = currentPrice;

        Color color = overlayRising ? Color.RED : Color.BLUE;
        ValueMarker marker = new ValueMarker(currentPrice);
        marker.setPaint(color);
        marker.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, new float[]{5.0f}, 0.0f));
        plot.addRangeMarker(marker, org.jfree.chart.ui.Layer.FOREGROUND);

        SwingUtilities.invokeLater(() -> chartPanel.repaint());
    }


    // ════════════════════════════════════════════════════
    //  가격 포맷
    // ════════════════════════════════════════════════════

    private DecimalFormat buildPriceFormat() {
        return new DecimalFormat("#,##0.##########");
    }

    private String formatPrice(double price) {
        return buildPriceFormat().format(price);
    }


    // ════════════════════════════════════════════════════
    //  가격 박스 오버레이
    // ════════════════════════════════════════════════════

    private class OverlayChartPanel extends ChartPanel {

        OverlayChartPanel(JFreeChart chart) { super(chart); }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (Double.isNaN(overlayPrice) || plot == null) return;

            Rectangle2D plotArea = getScreenDataArea();
            if (plotArea == null) return;

            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double yPixel = yAxis.valueToJava2D(overlayPrice, plotArea, plot.getRangeAxisEdge());

            int boxW = 110, boxH = 20;
            int boxX = (int) plotArea.getMaxX() + 2;
            int boxY = (int) (yPixel - boxH / 2.0);

            if (boxY < 0 || boxY + boxH > getHeight()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(overlayRising ? Color.RED : Color.BLUE);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 12));
            String text = formatPrice(overlayPrice);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(text);
            if (textW + 10 > boxW) {
                g2.setColor(overlayRising ? Color.RED : Color.BLUE);
                g2.fillRoundRect(boxX, boxY, textW + 10, boxH, 4, 4);
                g2.setColor(Color.WHITE);
            }
            g2.drawString(text, boxX + 5, boxY + (boxH + fm.getAscent() - fm.getDescent()) / 2);
            g2.dispose();
        }
    }


    // ════════════════════════════════════════════════════
    //  줌
    // ════════════════════════════════════════════════════

    private void handleFixedRightZoom(int wheelRotation) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        OHLCDataset dataset = (OHLCDataset) plot.getDataset();
        if (dataset.getItemCount(0) <= 1) return;

        double candleMs  = getCandleIntervalMs(currentTimeframe);
        double upper     = domainAxis.getRange().getUpperBound();
        double length    = domainAxis.getRange().getLength();
        int visibleCount = (int) Math.round(length / candleMs);

        if (wheelRotation > 0) {
            // 줌 아웃 — MAX_VISIBLE_CANDLES 초과 불가
            visibleCount = Math.min(MAX_VISIBLE_CANDLES, visibleCount + 5);
        } else {
            // 줌 인 — MIN_VISIBLE_CANDLES 미만 불가
            visibleCount = Math.max(MIN_VISIBLE_CANDLES, visibleCount - 5);
        }

        double newLen = visibleCount * candleMs;
        domainAxis.setRange(upper - newLen, upper);

        updateYAxisRange(dataset);
        checkAndPrefetchIfNeeded(currentTimeframe); // ★ 줌 후 pre-fetch 체크
        chartPanel.repaint();
    }


    // ════════════════════════════════════════════════════
    //  라이브 캔들 (실시간)
    // ════════════════════════════════════════════════════

    private void startLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
        liveTimer = new Timer(LIVE_RENDER_MS, e -> onLiveTick());
        liveTimer.start();
    }

    public void stopLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
    }

    private void onLiveTick() {
        if (backtestTargetTime != null || latestLivePrice <= 0) return;

        LocalDateTime serverNow;
        if (latestLiveTimestamp > 0) {
            serverNow = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(latestLiveTimestamp),
                    ZoneId.of("Asia/Seoul"));
        } else {
            serverNow = LocalDateTime.now();
        }

        LocalDateTime currentMinuteStart = serverNow.truncatedTo(ChronoUnit.MINUTES);

        if (liveCandleMinuteStart == null || !liveCandleMinuteStart.equals(currentMinuteStart)) {
            liveCandleMinuteStart = currentMinuteStart;
            liveCandle = createNewLiveCandle(latestLivePrice, currentMinuteStart);
        }

        double price = latestLivePrice;
        liveCandle.setTradePrice(price);
        liveCandle.setHighPrice(Math.max(liveCandle.getHighPrice(), price));
        liveCandle.setLowPrice(Math.min(liveCandle.getLowPrice(), price));

        SwingUtilities.invokeLater(() -> {
            // 캐시 재사용 — DB 재조회 없음
            OHLCDataset dataset = createDatasetWithLiveCandle(currentMarket, currentTimeframe);
            plot.setDataset(dataset);
            updateYAxisRange(dataset);
            drawCurrentPriceDashLine(dataset);
        });
    }

    public void setLatestPriceFromWebSocket(double price, long serverTimestamp) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice     = price;
        this.latestLiveTimestamp = serverTimestamp;
    }

    public void setLatestPriceFromWebSocket(double price) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice = price;
    }

    private CandleDTO createNewLiveCandle(double price, LocalDateTime minuteStart) {
        CandleDTO c = new CandleDTO();
        c.setMarket(currentMarket);
        c.setCandleDateTimeKst(minuteStart);
        c.setCandleDateTimeUtc(minuteStart.minusHours(9));
        c.setOpeningPrice(price);
        c.setHighPrice(price);
        c.setLowPrice(price);
        c.setTradePrice(price);
        c.setTimestamp(System.currentTimeMillis());
        c.setUnit(1);
        return c;
    }


    // ════════════════════════════════════════════════════
    //  웹소켓
    // ════════════════════════════════════════════════════

    private void connectWebSocket() {
        disconnectWebSocket();
        webSocketClient = new UpbitWebSocket(currentMarket, this);
        webSocketClient.connect();
    }

    private void disconnectWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            webSocketClient = null;
        }
    }


    // ════════════════════════════════════════════════════
    //  공개 API
    // ════════════════════════════════════════════════════

    /** 코인 종목 변경 */
    public void changeMarket(String coinSymbol) {
        this.currentMarket = "KRW-" + coinSymbol;
        liveCandle = null;
        liveCandleMinuteStart = null;
        resetAllCaches(); // 종목 바뀌면 모든 캐시 초기화
        refreshChart();
        if (backtestTargetTime == null) connectWebSocket();
    }

    /** 백테스팅 모드로 전환 */
    public void loadHistoricalData(LocalDateTime targetTime) {
        stopLiveTimer();
        disconnectWebSocket();
        liveCandle = null;
        liveCandleMinuteStart = null;
        resetAllCaches();
        this.backtestTargetTime = targetTime;

        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, targetTime);
            plot.setDataset(dataset);
            updateXAxisRange(dataset);
            updateYAxisRange(dataset);
            drawCurrentPriceDashLine(dataset);
            applyDateAxisFormat();
            chart.setTitle(getCurrentMarketSymbol() + " (Backtesting)");
        });
    }

    /** 실시간 모드로 복귀 */
    public void resetToRealtimeMode() {
        this.backtestTargetTime = null;
        liveCandle = null;
        liveCandleMinuteStart = null;
        resetAllCaches();
        startLiveTimer();
        connectWebSocket();
        refreshChart();
    }

    private String getCurrentMarketSymbol() {
        return currentMarket.replace("KRW-", "");
    }
}