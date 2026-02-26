package com.team.coin_simulator;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.math.BigDecimal;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.team.coin_simulator.Alerts.PriceAlertService;
import com.team.coin_simulator.Market_Order.OrderPanel;
import com.team.coin_simulator.Market_Panel.HistoryPanel;
import com.team.coin_simulator.backtest.BacktestSessionDialog;
import com.team.coin_simulator.backtest.BacktestTimeControlPanel;
import com.team.coin_simulator.backtest.BacktestTimeController;
import com.team.coin_simulator.backtest.CandleChartBacktestAdapter;
import com.team.coin_simulator.backtest.SessionManager;
import com.team.coin_simulator.chart.CandleChartPanel;
import com.team.coin_simulator.orderbook.OrderBookPanel;

import DAO.UpbitWebSocketDao;
import DTO.SessionDTO;
import Investment_details.Investment_details_MainPanel;
import databasetestdata.DownloadDatabase;

/**
 * 메인 프레임 - 전체 화면 전환 방식
 *
 * 화면 구성:
 * 1. 거래 화면 (Trading View): 코인목록 + 차트 + 호가창 + 주문
 * 2. 투자내역 화면 (Investment View): 보유자산/투자손익/거래내역/미결
 *
 * ※ TimeController / TimeControlPanel 제거 —
 *   실시간 ↔ 백테스팅 전환은 BacktestTimeController + BacktestTimeControlPanel이 전담합니다.
 */
public class MainFrame extends JFrame {

    // 상단 패널
    private JPanel topPanel;
    private JButton btnToggleView;

    // 메인 컨텐츠 (CardLayout)
    private CardLayout mainCardLayout;
    private JPanel mainContentPanel;

    // 거래 화면 컴포넌트
    private JPanel tradingPanel;
    private HistoryPanel historyPanel;
    private CandleChartPanel chartPanel;
    private OrderBookPanel orderBookPanel;
    private OrderPanel orderPanel;

    // 투자내역 화면 컴포넌트
    private Investment_details_MainPanel investmentPanel;
    
    // 현재 선택된 코인 심볼 (호가창 모드 전환 시 필요)
    private String currentCoinSymbol = "BTC";

 // --- MainFrame 필드에 추가 ---
    private volatile boolean shuttingDown = false;
    private Thread marketSyncThread;
    
    // 상태 관리
    private String currentUserId = "test_user1";
    private boolean isTradingView = true;

    // 카드 식별자
    private static final String CARD_TRADING   = "TRADING";
    private static final String CARD_INVESTMENT = "INVESTMENT";

    // 알림 감시자
    private PriceAlertService alertService;
    private JButton btnAlert;
    
    //자동 매매 엔진
    private com.team.coin_simulator.Market_Order.AutoOrderService autoOrderService;

    // 백테스팅 UI / 어댑터
    private BacktestTimeControlPanel backtestControlPanel;
    private CandleChartBacktestAdapter chartBacktestAdapter;
    
    //profile 버튼 
    private JButton btnProfile;
    
    private long currentSessionId = SessionManager.getInstance().getCurrentSessionId();


    // ════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════

    public MainFrame(String userId) {
        super("가상화폐 모의투자 시스템");
        this.currentUserId = userId;
        
        com.team.coin_simulator.backtest.BacktestSessionDAO sessionDAO = new com.team.coin_simulator.backtest.BacktestSessionDAO();
        DTO.SessionDTO realtimeSession = sessionDAO.getOrCreateRealtimeSession(this.currentUserId);
        com.team.coin_simulator.backtest.SessionManager.getInstance().setCurrentSession(realtimeSession);
        
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1600, 900);
        setLocationRelativeTo(null);

        alertService = new PriceAlertService(this, this.currentUserId);
        
        autoOrderService = new com.team.coin_simulator.Market_Order.AutoOrderService(this, currentUserId, currentSessionId);
        
        initComponents();
        initWebSocket(); // 앱 시작 시 항상 실시간 WebSocket 연결
        
        setVisible(true);
        syncMarketDataBackground();
    }

    // ════════════════════════════════════════════════
    //  초기화
    // ════════════════════════════════════════════════

    private void syncMarketDataBackground() {
        marketSyncThread = new Thread(() -> {
            System.out.println("[MainFrame] 백그라운드에서 캔들 데이터 동기화를 시작합니다...");
            try {

                // 로그아웃/종료 중이면 실행 안 함
                if (shuttingDown || Thread.currentThread().isInterrupted()) return;

                if (shuttingDown || Thread.currentThread().isInterrupted()) return;

                System.out.println("[MainFrame] 데이터 동기화 완료!");
                
                SwingUtilities.invokeLater(() -> {
                    // ✅ 이미 로그아웃/종료면 UI 접근 금지
                    if (shuttingDown) return;

                    if (investmentPanel != null) {
                        investmentPanel.setSessionId(currentSessionId);
                        investmentPanel.refreshAll();
                    }
                    System.out.println("[MainFrame] UI 데이터 갱신 완료");
                });

            } catch (Exception e) {
                if (!shuttingDown) {
                    System.err.println("[MainFrame] 데이터 동기화 중 오류 발생: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        }, "MarketSyncThread");

        marketSyncThread.setDaemon(true); // 앱 종료에 방해 안 되게
        marketSyncThread.start();
    }
    private void initComponents() {
        setLayout(new BorderLayout());

        // 1. 상단 패널
        topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);

        // 2. 메인 컨텐츠 영역
        mainCardLayout   = new CardLayout();
        mainContentPanel = new JPanel(mainCardLayout);

        tradingPanel    = createTradingPanel();
        investmentPanel = new Investment_details_MainPanel(currentUserId, currentSessionId);

        chartBacktestAdapter = new CandleChartBacktestAdapter(chartPanel, historyPanel, orderPanel);
        BacktestTimeController.getInstance().addTickListener(chartBacktestAdapter);

        mainContentPanel.add(tradingPanel,    CARD_TRADING);
        mainContentPanel.add(investmentPanel, CARD_INVESTMENT);

        add(mainContentPanel, BorderLayout.CENTER);
        mainCardLayout.show(mainContentPanel, CARD_TRADING);
    }

    /** 상단 패널 — BacktestTimeControlPanel + 화면 전환 버튼 */
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);

        backtestControlPanel = new BacktestTimeControlPanel(this, currentUserId);
        panel.add(backtestControlPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 10));
        buttonPanel.setBackground(Color.WHITE);

        // ✅ 투자내역 보기 버튼 (폭 줄임)
        btnToggleView = new JButton("투자내역 보기");
        btnToggleView.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btnToggleView.setForeground(Color.WHITE);
        btnToggleView.setBackground(new Color(52, 152, 219));
        btnToggleView.setFocusPainted(false);
        btnToggleView.setBorderPainted(false);
        btnToggleView.setPreferredSize(new Dimension(120, 35)); // 🔥 150 -> 120
        btnToggleView.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnToggleView.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { btnToggleView.setBackground(new Color(41, 128, 185)); }
            public void mouseExited(java.awt.event.MouseEvent evt)  { btnToggleView.setBackground(new Color(52, 152, 219)); }
        });

        btnToggleView.addActionListener(e -> toggleView());
        buttonPanel.add(btnToggleView);

        // ✅ 프로필 버튼 (투자내역 보기 "오른쪽")
        btnProfile = new JButton("내 프로필");
        btnProfile.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btnProfile.setForeground(Color.WHITE);
        btnProfile.setBackground(new Color(155, 89, 182));
        btnProfile.setFocusPainted(false);
        btnProfile.setBorderPainted(false);
        btnProfile.setPreferredSize(new Dimension(110, 35));
        btnProfile.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnProfile.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { btnProfile.setBackground(new Color(142, 68, 173)); }
            public void mouseExited(java.awt.event.MouseEvent evt)  { btnProfile.setBackground(new Color(155, 89, 182)); }
        });

        btnProfile.addActionListener(e -> openProfile());
        buttonPanel.add(btnProfile);
        
        //가격 알림 설정 버튼
        btnAlert = new JButton("알림 설정");
        btnAlert.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btnAlert.setForeground(Color.WHITE);
        btnAlert.setBackground(new Color(243, 156, 18)); // 예쁜 오렌지색!
        btnAlert.setFocusPainted(false);
        btnAlert.setBorderPainted(false);
        btnAlert.setPreferredSize(new Dimension(110, 35));
        btnAlert.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // 마우스 올렸을 때 색상 변화 효과
        btnAlert.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { btnAlert.setBackground(new Color(230, 126, 34)); }
            public void mouseExited(java.awt.event.MouseEvent evt)  { btnAlert.setBackground(new Color(243, 156, 18)); }
        });

        // 버튼 클릭 시 팝업창 띄우기 로직
        btnAlert.addActionListener(e -> {
            // 1. 코인 이름에 "KRW-" 붙여주기 (예: BTC -> KRW-BTC)
            String market = "KRW-" + currentCoinSymbol; 
            
            // 2. OrderPanel에서 현재가 훔쳐오기 (없으면 0원)
            BigDecimal currentPrice = (orderPanel != null) ? orderPanel.getCurrentSelectedPrice() : BigDecimal.ZERO;
            
            //AlertDialog 띄우기 (자신, 코인명, 현재가, 유저ID, 알림서비스)
            new com.team.coin_simulator.Alerts.AlertDialog(
                MainFrame.this, market, currentPrice, currentUserId, alertService
            ).setVisible(true);
        });

        buttonPanel.add(btnAlert); // 패널에 버튼 장착

        panel.add(buttonPanel, BorderLayout.EAST);
        return panel;
    }

    
    /** 거래 화면 생성 */
    private JPanel createTradingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);

        historyPanel = new HistoryPanel(currentUserId, currentSessionId);
        historyPanel.setPreferredSize(new Dimension(350, 0));
        historyPanel.addCoinSelectionListener(this::onCoinSelected);

        JPanel centerArea = new JPanel(new BorderLayout());

        chartPanel = new CandleChartPanel("BTC 차트");
        chartPanel.setPreferredSize(new Dimension(0, 500));

        orderBookPanel = new OrderBookPanel("BTC");
        orderBookPanel.setPreferredSize(new Dimension(0, 350));
        orderBookPanel.setBorder(BorderFactory.createTitledBorder("호가창"));

        centerArea.add(chartPanel,    BorderLayout.CENTER);
        centerArea.add(orderBookPanel, BorderLayout.SOUTH);

        orderPanel = new OrderPanel(currentUserId, false);
        orderPanel.setPreferredSize(new Dimension(350, 0));

        panel.add(historyPanel,  BorderLayout.WEST);
        panel.add(centerArea,    BorderLayout.CENTER);
        panel.add(orderPanel,    BorderLayout.EAST);
        return panel;
    }

    // ════════════════════════════════════════════════
    //  백테스팅 세션 다이얼로그 (BacktestTimeControlPanel에서 호출)
    // ════════════════════════════════════════════════

    public void openSessionDialog() {
        BacktestSessionDialog dialog = new BacktestSessionDialog(this, currentUserId);
        dialog.setVisible(true);

        SessionDTO selectedSession = dialog.getSelectedSession();
        if (selectedSession == null) return;

        SessionManager.getInstance().setCurrentSession(selectedSession);
        this.currentSessionId = selectedSession.getSessionId();

        System.out.println("[MainFrame] 선택된 세션 ID: " + currentSessionId);

        if (investmentPanel != null) {
            investmentPanel.setSessionId(currentSessionId);
            investmentPanel.refreshAll();
        }
        
        if (historyPanel != null) {
            historyPanel.setSessionId(currentSessionId);
        }
        if (orderPanel != null) {
            orderPanel.setSessionId(currentSessionId);
        }
        
        java.time.LocalDateTime startTime   = selectedSession.getStartSimTime().toLocalDateTime();
        java.time.LocalDateTime currentSimTime = selectedSession.getCurrentSimTime() != null
                ? selectedSession.getCurrentSimTime().toLocalDateTime()
                : startTime;
        java.time.LocalDateTime endTime = startTime.plusMonths(1);

        // 실시간 WebSocket 종료 & 차트 백테스팅 시점 설정
        UpbitWebSocketDao.getInstance().close();
        chartPanel.loadHistoricalData(currentSimTime);

        //호가창을 백테스팅 모드로 전환
        if (orderBookPanel != null) {
            orderBookPanel.switchToBacktestMode(currentCoinSymbol);
        }
        
        // 백테스팅 엔진 시작
        BacktestTimeController.getInstance().startSession(
                currentUserId, currentSessionId, startTime, currentSimTime, endTime);

        if (backtestControlPanel != null) {
            backtestControlPanel.activateSessionUI(selectedSession);
        }
        //백테스팅 진입 시 알림 버튼 숨기기
        if (btnAlert != null) {
            btnAlert.setVisible(false); // 버튼 화면에서 삭제
        }
    }

    /**
     * 백테스팅 → 실시간 복귀 시 BacktestTimeControlPanel이 호출합니다.
     * (구 TimeController.switchToRealtimeMode() 역할)
     */
    public void returnToRealtimeMode() {
        SwingUtilities.invokeLater(() -> {
            System.out.println("[MainFrame] 실시간 모드로 전환됨");
            
            // 1.SessionManager에게 "실시간 세션으로 돌아왔다!"고 확실히 알려줍니다.
            com.team.coin_simulator.backtest.BacktestSessionDAO sessionDAO = new com.team.coin_simulator.backtest.BacktestSessionDAO();
            DTO.SessionDTO realtimeSession = sessionDAO.getOrCreateRealtimeSession(this.currentUserId);
            com.team.coin_simulator.backtest.SessionManager.getInstance().setCurrentSession(realtimeSession);
            
            // DB에서 가져온 진짜 실시간 세션 ID (보통 1L이 맞지만, 더 안전한 방식)
            long realSessionId = realtimeSession.getSessionId();
            this.currentSessionId = realSessionId;
            
            // 2. 각 패널들에게 실시간 모드로 돌아왔으니 새로고침(refresh) 하라고 지시
            if (investmentPanel != null) investmentPanel.setSessionId(realSessionId);
            if (historyPanel != null) historyPanel.setSessionId(realSessionId);
            
            if (orderPanel != null) {
                // OrderPanel은 이 메서드가 호출되면 SessionManager를 확인하고 실시간 잔고를 가져옵니다!
                orderPanel.setSessionId(realSessionId); 
            }
            
            // 3. 차트 실시간 복귀 및 업비트 웹소켓 다시 연결
            chartPanel.resetToRealtimeMode();
            
            //추가: 호가창을 실시간 모드로 복귀
            if (orderBookPanel != null) {
                orderBookPanel.switchToRealtimeMode(currentCoinSymbol);
            }
            
            UpbitWebSocketDao.getInstance().start();
            
            if (alertService != null) {
                DAO.UpbitWebSocketDao.getInstance().addListener(alertService);
            }
            //실시간 복귀 시 알림 버튼 복구
            if (btnAlert != null) {
                btnAlert.setVisible(true);
            }
        });
    }

    // ════════════════════════════════════════════════
    //  화면 전환 / 이벤트 핸들러
    // ════════════════════════════════════════════════

    private void shutdownBackgroundTasks() {
        shuttingDown = true;

        // 1) WebSocket 종료
        try { UpbitWebSocketDao.getInstance().close(); } catch (Exception ignored) {}

        // 2) 호가창 연결 종료
        try { if (orderBookPanel != null) orderBookPanel.closeConnection(); } catch (Exception ignored) {}

        // 3) 주문패널(자동체결/타이머) 종료 훅 (아래 2번에서 OrderPanel에 메서드 추가할거임)
        try { if (orderPanel != null) orderPanel.shutdown(); } catch (Exception ignored) {}

        // 4) 마켓 동기화 스레드 interrupt
        try { if (marketSyncThread != null) marketSyncThread.interrupt(); } catch (Exception ignored) {}
    }
    
    private void toggleView() {
        isTradingView = !isTradingView;
        if (isTradingView) {
            mainCardLayout.show(mainContentPanel, CARD_TRADING);
            btnToggleView.setText("투자내역 보기");
            btnToggleView.setBackground(new Color(52, 152, 219));
        } else {
            mainCardLayout.show(mainContentPanel, CARD_INVESTMENT);
            btnToggleView.setText("거래화면 보기");
            btnToggleView.setBackground(new Color(46, 204, 113));
            investmentPanel.refreshAll();
        }
    }

    private void onCoinSelected(String coinSymbol) {
        if (coinSymbol == null || coinSymbol.isEmpty()) return;
        System.out.println("[MainFrame] 코인 선택됨: " + coinSymbol);

        this.currentCoinSymbol = coinSymbol; //현재 코인 심볼 저장
        
        chartPanel.changeMarket(coinSymbol);
        updateOrderBookPanel(coinSymbol);

        if (orderPanel != null) {
            orderPanel.setSelectedCoin(coinSymbol);
        }
        //알림 엔진 다시 연결
        if (alertService != null) {
            DAO.UpbitWebSocketDao.getInstance().addListener(alertService);
        }
        if (autoOrderService != null) {
            DAO.UpbitWebSocketDao.getInstance().addListener(autoOrderService);
        }
    }

    private void updateOrderBookPanel(String coinSymbol) {
        if (orderBookPanel != null) orderBookPanel.closeConnection();

        orderBookPanel = new OrderBookPanel(coinSymbol);
        orderBookPanel.setPreferredSize(new Dimension(0, 350));
        orderBookPanel.setBorder(BorderFactory.createTitledBorder(coinSymbol + " 호가창"));

        //추가: 현재 백테스팅 진행 중이면 새 패널도 백테스팅 모드로 전환
        if (BacktestTimeController.getInstance().isRunning()) {
            orderBookPanel.switchToBacktestMode(coinSymbol);
        }
        
        JPanel centerArea = (JPanel) ((JPanel) tradingPanel.getComponent(1));
        centerArea.remove(1);
        centerArea.add(orderBookPanel, BorderLayout.SOUTH);
        centerArea.revalidate();
        centerArea.repaint();
    }

    // ════════════════════════════════════════════════
    //  WebSocket / 리소스
    // ════════════════════════════════════════════════
    
    //프로필 버튼 클릭시 열리게
    private void openProfile() {
        new com.team.coin_simulator.profile.ProfileDialog(this, currentUserId).setVisible(true);
    }
    
    private void initWebSocket() {
        UpbitWebSocketDao.getInstance().start();
    }

    @Override
    public void dispose() {
        shutdownBackgroundTasks();

        // ❌ 여기서 DBConnection.close() 절대 호출하지마 (로그아웃도 dispose 되니까)
        super.dispose();
    }

    //진입점
    public static void main(String[] args) {

        // 프로그램 완전 종료시에만 DB 풀 닫기
        Runtime.getRuntime().addShutdownHook(new Thread(DBConnection::close));

        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }

        SwingUtilities.invokeLater(() -> new MainFrame("test_user1"));
    }
    //OrderPanel에서 자동 매매 엔진을 가져갈 수 있게 해주는 메서드
    public com.team.coin_simulator.Market_Order.AutoOrderService getAutoOrderService() {
        return this.autoOrderService;
    }
}