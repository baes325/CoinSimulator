package com.team.coin_simulator.backtest;

import DTO.SessionDTO;
import com.team.coin_simulator.Alerts.NotificationUtil;
import com.team.coin_simulator.MainFrame;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 백테스팅 전용 시간 제어 패널
 *
 * ■ 구성
 *   [세션 선택] | 시뮬레이션 시간 | 진행률 바 | [배속 버튼 4종] | [일시정지/재개] | [실시간 모드 복귀]
 *
 * ※ TimeController 의존 완전 제거 —
 *   실시간 복귀 시 MainFrame.returnToRealtimeMode() 를 직접 호출합니다.
 */
public class BacktestTimeControlPanel extends JPanel
        implements BacktestTimeController.BacktestTickListener,
                   BacktestTimeController.BacktestEventListener {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    // ── 의존성 ────────────────────────────────────────
    private final JFrame                 parentFrame;
    private final String                 userId;
    private final BacktestTimeController engine     = BacktestTimeController.getInstance();
    private final BacktestSessionDAO     sessionDAO = new BacktestSessionDAO();

    // ── 현재 세션 ─────────────────────────────────────
    private SessionDTO currentSession = null;

    // ── UI 컴포넌트 ───────────────────────────────────
    private JLabel       lblSimTime;
    private JLabel       lblRemaining;
    private JProgressBar progressBar;
    private JButton      btnPause;
    private JButton[]    speedButtons;
    private JButton      btnSelectSession;

    // 배속 버튼 강조 색상
    private static final Color CLR_ACTIVE  = new Color(255, 140, 0);
    private static final Color CLR_NORMAL  = new Color(230, 230, 230);
    private static final Color CLR_BLOCKED = new Color(200, 200, 200);

    // ════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════

    public BacktestTimeControlPanel(JFrame parentFrame, String userId) {
        this.parentFrame = parentFrame;
        this.userId      = userId;

        setBackground(Color.WHITE);
        setLayout(new BorderLayout(8, 0));
        setBorder(new EmptyBorder(6, 10, 6, 10));
        setPreferredSize(new Dimension(0, 65));

        engine.addTickListener(this);
        engine.addEventListener(this);

        buildUI();
        setControlsEnabled(false);
    }

    // ════════════════════════════════════════════════
    //  UI 구성
    // ════════════════════════════════════════════════

    private void buildUI() {
        // ── 왼쪽: 세션 선택 버튼 ─────────────────────
        btnSelectSession = new JButton("세션 선택");
        styleBtn(btnSelectSession, new Color(52, 152, 219), Color.WHITE);
        btnSelectSession.addActionListener(e -> {
            if (parentFrame instanceof MainFrame) {
                ((MainFrame) parentFrame).openSessionDialog();
            }
        });

        JPanel leftPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        leftPanel.setOpaque(false);
        leftPanel.add(btnSelectSession);

        // ── 중앙: 시간 정보 & 진행률 ─────────────────
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        lblSimTime = new JLabel("세션을 선택하세요");
        lblSimTime.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        lblSimTime.setForeground(new Color(255, 140, 0));
        lblSimTime.setAlignmentX(Component.CENTER_ALIGNMENT);

        lblRemaining = new JLabel(" ");
        lblRemaining.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        lblRemaining.setForeground(Color.GRAY);
        lblRemaining.setAlignmentX(Component.CENTER_ALIGNMENT);

        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(false);
        progressBar.setForeground(new Color(255, 140, 0));
        progressBar.setBackground(new Color(240, 240, 240));
        progressBar.setBorder(BorderFactory.createEmptyBorder());
        progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));

        centerPanel.add(lblSimTime);
        centerPanel.add(Box.createVerticalStrut(2));
        centerPanel.add(progressBar);
        centerPanel.add(Box.createVerticalStrut(1));
        centerPanel.add(lblRemaining);

        // ── 오른쪽: 배속 + 제어 버튼 ─────────────────
        JPanel rightPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        rightPanel.setOpaque(false);

        BacktestSpeed[] speeds = BacktestSpeed.values();
        speedButtons = new JButton[speeds.length];
        JPanel speedPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        speedPanel.setOpaque(false);

        for (int i = 0; i < speeds.length; i++) {
            final BacktestSpeed spd = speeds[i];
            JButton btn = new JButton(spd.getLabel());
            btn.setFont(new Font("맑은 고딕", Font.BOLD, 11));
            btn.setFocusPainted(false);
            btn.setBackground(CLR_NORMAL);
            btn.setPreferredSize(new Dimension(68, 30));
            btn.addActionListener(e -> changeSpeed(spd));
            speedButtons[i] = btn;
            speedPanel.add(btn);
        }

        btnPause = new JButton("일시정지");
        styleBtn(btnPause, new Color(149, 165, 166), Color.WHITE);
        btnPause.addActionListener(e -> togglePause());

        JButton btnRealtime = new JButton("실시간 복귀");
        styleBtn(btnRealtime, new Color(231, 76, 60), Color.WHITE);
        btnRealtime.addActionListener(e -> returnToRealtime());

        rightPanel.add(speedPanel);
        rightPanel.add(btnPause);
        rightPanel.add(btnRealtime);

        add(leftPanel,   BorderLayout.WEST);
        add(centerPanel, BorderLayout.CENTER);
        add(rightPanel,  BorderLayout.EAST);
    }

    // ════════════════════════════════════════════════
    //  세션 활성화 (MainFrame.openSessionDialog 에서 호출)
    // ════════════════════════════════════════════════

    public void activateSessionUI(SessionDTO session) {
        this.currentSession = session;
        setControlsEnabled(true);
        highlightSpeedButton(BacktestSpeed.SPEED_1X);
        NotificationUtil.showToast(parentFrame, "백테스팅 시작: " + session.getSessionName());
    }

    // ════════════════════════════════════════════════
    //  BacktestTickListener
    // ════════════════════════════════════════════════

    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        SwingUtilities.invokeLater(() -> {
            lblSimTime.setText("● " + currentSimTime.format(FMT));

            int pct = (int) (engine.getProgress() * 100);
            progressBar.setValue(pct);

            long remainMin = engine.getRemainingMinutes();
            String remainStr;
            if (remainMin > 60 * 24) {
                remainStr = "남은 기간: " + remainMin / (60 * 24) + "일 "
                            + (remainMin % (60 * 24)) / 60 + "시간";
            } else {
                remainStr = "남은 기간: " + remainMin / 60 + "시간 " + remainMin % 60 + "분";
            }

            if (engine.isInFinalWeek()) {
                lblRemaining.setForeground(Color.RED);
                remainStr = "⚠ [마지막 1주일] " + remainStr;
            } else {
                lblRemaining.setForeground(Color.GRAY);
            }
            lblRemaining.setText(remainStr);
        });
    }

    // ════════════════════════════════════════════════
    //  BacktestEventListener
    // ════════════════════════════════════════════════

    @Override
    public void onFinalWeekWarning(boolean hasPositions) {
        SwingUtilities.invokeLater(() -> {
            progressBar.setForeground(Color.RED);

            if (hasPositions) {
                int choice = JOptionPane.showConfirmDialog(
                        parentFrame,
                        "⚠ 백테스팅 세션 종료까지 1주일이 남았습니다.\n\n"
                        + "현재 보유 중인 포지션이 있습니다.\n"
                        + "지금 포지션을 정리하시겠습니까?\n\n"
                        + "※ 배속이 최대 10배속으로 제한됩니다.",
                        "포지션 정리 권고",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE);

                if (choice == JOptionPane.YES_OPTION) {
                    NotificationUtil.showToast(parentFrame,
                            "💡 주문 패널에서 보유 코인을 매도하여 포지션을 정리하세요.");
                }
            } else {
                JOptionPane.showMessageDialog(
                        parentFrame,
                        "⚠ 백테스팅 세션 종료까지 1주일이 남았습니다.\n"
                        + "※ 배속이 최대 10배속으로 제한됩니다.",
                        "세션 종료 임박",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });
    }

    @Override
    public void onSpeedForced(BacktestSpeed newSpeed) {
        SwingUtilities.invokeLater(() -> {
            for (JButton btn : speedButtons) btn.setBackground(CLR_BLOCKED);
            highlightSpeedButton(newSpeed);
            for (int i = 2; i < speedButtons.length; i++) speedButtons[i].setEnabled(false);
            NotificationUtil.showToast(parentFrame,
                    "⚠ 종료 1주일 전 구간 — 배속이 " + newSpeed.getLabel() + " 으로 제한됩니다.");
        });
    }

    @Override
    public void onSessionEnded() {
        SwingUtilities.invokeLater(() -> {
            setControlsEnabled(false);
            lblSimTime.setText("세션 종료");
            progressBar.setValue(100);
            progressBar.setForeground(Color.GRAY);
            lblRemaining.setText("백테스팅이 완료되었습니다.");

            JOptionPane.showMessageDialog(
                    parentFrame,
                    "🏁 백테스팅 세션이 종료되었습니다!\n\n"
                    + "투자내역 화면에서 최종 성과를 확인해보세요.",
                    "세션 완료",
                    JOptionPane.INFORMATION_MESSAGE);
        });
    }

    // ════════════════════════════════════════════════
    //  버튼 액션
    // ════════════════════════════════════════════════

    private void changeSpeed(BacktestSpeed requestedSpeed) {
        if (!engine.isRunning()) return;
        BacktestSpeed actual = engine.setSpeed(requestedSpeed);
        highlightSpeedButton(actual);
    }

    private void togglePause() {
        if (!engine.isRunning()) return;
        if (engine.isPaused()) {
            engine.resume();
            btnPause.setText("⏸ 일시정지");
            btnPause.setBackground(new Color(149, 165, 166));
        } else {
            engine.pause();
            btnPause.setText("▶ 재개");
            btnPause.setBackground(new Color(46, 204, 113));
        }
    }

    /**
     * 백테스팅 중단 후 실시간 모드로 복귀합니다.
     * TimeController 를 거치지 않고 MainFrame.returnToRealtimeMode() 를 직접 호출합니다.
     */
    private void returnToRealtime() {
        int choice = JOptionPane.showConfirmDialog(
                parentFrame,
                "백테스팅을 중단하고 실시간 모드로 전환하시겠습니까?",
                "실시간 복귀",
                JOptionPane.YES_NO_OPTION);

        if (choice == JOptionPane.YES_OPTION) {
            engine.stop();
            setControlsEnabled(false);
            lblSimTime.setText("실시간 모드");
            lblRemaining.setText(" ");
            progressBar.setValue(0);
            progressBar.setForeground(new Color(255, 140, 0));

            // ★ TimeController 대신 MainFrame 직접 호출
            if (parentFrame instanceof MainFrame) {
                ((MainFrame) parentFrame).returnToRealtimeMode();
            }
        }
    }

    // ════════════════════════════════════════════════
    //  보조 UI
    // ════════════════════════════════════════════════

    private void highlightSpeedButton(BacktestSpeed speed) {
        BacktestSpeed[] speeds = BacktestSpeed.values();
        for (int i = 0; i < speeds.length; i++) {
            speedButtons[i].setBackground(speeds[i] == speed ? CLR_ACTIVE : CLR_NORMAL);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        btnPause.setEnabled(enabled);
        for (JButton btn : speedButtons) btn.setEnabled(enabled);
    }

    private void styleBtn(JButton btn, Color bg, Color fg) {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(100, 30));
    }
}