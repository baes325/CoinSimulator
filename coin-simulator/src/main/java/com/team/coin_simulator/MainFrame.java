package com.team.coin_simulator;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

import com.team.coin_simulator.Alerts.PriceAlertService;
import com.team.coin_simulator.Market_Order.OrderPanel;
import com.team.coin_simulator.Market_Panel.HistoryPanel;
import com.team.coin_simulator.chart.CandleChartPanel;
import com.team.coin_simulator.orderbook.OrderBookPanel;

import DAO.UpbitWebSocketDao;
import Investment_details.Investment_details_MainPanel;
import databasetestdata.DownloadDatabase;

/**
 * 메인 프레임 - 전체 화면 전환 방식
 * 
 * 화면 구성:
 * 1. 거래 화면 (Trading View): 코인목록 + 차트 + 호가창 + 주문
 * 2. 투자내역 화면 (Investment View): 보유자산/투자손익/거래내역/미결
 * 
 * 상단 버튼으로 두 화면 전환
 */

public class MainFrame extends JFrame implements TimeController.TimeChangeListener {
    
    // 상단 패널
    private JPanel topPanel;
    private TimeControlPanel timeControlPanel;
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
    
    // 상태 관리
    private TimeController timeController;

    private String currentUserId = "test_user1"; // 로그인 시스템 구현 전 임시 사용자
    private boolean isTradingView = true; // true: 거래화면, false: 투자내역
    
    // 카드 식별자
    private static final String CARD_TRADING = "TRADING";
    private static final String CARD_INVESTMENT = "INVESTMENT";
  
    //알림 감시자
    private PriceAlertService alertService;

    public MainFrame(String userId) {
        super("가상화폐 모의투자 시스템");
        this.currentUserId = userId;
        
        timeController = TimeController.getInstance();
        timeController.initialize(currentUserId);
        timeController.addTimeChangeListener(this);
        
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1600, 900);
        setLocationRelativeTo(null);
        
        initComponents();
        initWebSocket();
        
        // 알림 서비스 (프레임 정보를 넘겨줌)
        alertService = new PriceAlertService(this);
        
        setVisible(true);
    }
    
    private void initComponents() {
        setLayout(new BorderLayout());
        
        // 1. 상단 패널 (시간 제어 + 화면 전환 버튼)
        topPanel = createTopPanel();
        add(topPanel, BorderLayout.NORTH);
        
        // 2. 메인 컨텐츠 영역 (CardLayout으로 화면 전환)
        mainCardLayout = new CardLayout();
        mainContentPanel = new JPanel(mainCardLayout);
        
        // 2-1. 거래 화면 생성
        tradingPanel = createTradingPanel();
        
        // 2-2. 투자내역 화면 생성
        investmentPanel = new Investment_details_MainPanel(currentUserId);
        
        // 카드에 추가
        mainContentPanel.add(tradingPanel, CARD_TRADING);
        mainContentPanel.add(investmentPanel, CARD_INVESTMENT);
        
        add(mainContentPanel, BorderLayout.CENTER);
        
        // 기본 화면: 거래 화면
        mainCardLayout.show(mainContentPanel, CARD_TRADING);
    }
    
    /**
     * 상단 패널 생성 (시간 제어 + 화면 전환 버튼)
     */
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        
        // 왼쪽: 시간 제어 패널
        timeControlPanel = new TimeControlPanel();
        panel.add(timeControlPanel, BorderLayout.CENTER);
        
        // 오른쪽: 화면 전환 버튼
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 15, 10));
        buttonPanel.setBackground(Color.WHITE);
        
        btnToggleView = new JButton("투자내역 보기");
        btnToggleView.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btnToggleView.setForeground(Color.WHITE);
        btnToggleView.setBackground(new Color(52, 152, 219));
        btnToggleView.setFocusPainted(false);
        btnToggleView.setBorderPainted(false);
        btnToggleView.setPreferredSize(new Dimension(150, 35));
        btnToggleView.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        
        // 호버 효과
        btnToggleView.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) {
                btnToggleView.setBackground(new Color(41, 128, 185));
            }
            public void mouseExited(java.awt.event.MouseEvent evt) {
                btnToggleView.setBackground(new Color(52, 152, 219));
            }
        });
        
        btnToggleView.addActionListener(e -> toggleView());
        buttonPanel.add(btnToggleView);
        
        panel.add(buttonPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * 거래 화면 생성
     */
    private JPanel createTradingPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        
        // 왼쪽: 코인 목록
        historyPanel = new HistoryPanel(currentUserId);
        historyPanel.setPreferredSize(new Dimension(350, 0));
        historyPanel.addCoinSelectionListener(this::onCoinSelected);
        
        // 중앙: 차트 + 호가창
        JPanel centerArea = new JPanel(new BorderLayout());
        
        chartPanel = new CandleChartPanel("BTC 차트");
        chartPanel.setPreferredSize(new Dimension(0, 500));
        
        orderBookPanel = new OrderBookPanel("BTC");
        orderBookPanel.setPreferredSize(new Dimension(0, 350));
        orderBookPanel.setBorder(BorderFactory.createTitledBorder("호가창"));
        
        centerArea.add(chartPanel, BorderLayout.CENTER);
        centerArea.add(orderBookPanel, BorderLayout.SOUTH);

        // 오른쪽: 주문 패널
        orderPanel = new OrderPanel(currentUserId);
        orderPanel.setPreferredSize(new Dimension(350, 0));
        
        // 조립
        panel.add(historyPanel, BorderLayout.WEST);
        panel.add(centerArea, BorderLayout.CENTER);
        panel.add(orderPanel, BorderLayout.EAST);
        
        return panel;
    }
    
    /**
     * 화면 전환 (거래 화면 ↔ 투자내역 화면)
     */
    private void toggleView() {
        isTradingView = !isTradingView;
        
        if (isTradingView) {
            // 거래 화면으로 전환
            mainCardLayout.show(mainContentPanel, CARD_TRADING);
            btnToggleView.setText("투자내역 보기");
            btnToggleView.setBackground(new Color(52, 152, 219));
            
        } else {
            // 투자내역 화면으로 전환
            mainCardLayout.show(mainContentPanel, CARD_INVESTMENT);
            btnToggleView.setText("거래화면 보기");
            btnToggleView.setBackground(new Color(46, 204, 113));
            
            // 투자내역 데이터 새로고침
            investmentPanel.refreshAll();
        }
    }
    
    /**
     * 코인 선택 이벤트 핸들러
     */
    private void onCoinSelected(String coinSymbol) {
        if (coinSymbol == null || coinSymbol.isEmpty()) {
            return;
        }
        
        System.out.println("[MainFrame] 코인 선택됨: " + coinSymbol);
        
        // 차트 업데이트
        chartPanel.changeMarket(coinSymbol);
        
        // 호가창 업데이트
        updateOrderBookPanel(coinSymbol);
        
        //3. 주문 패널(OrderPanel)에도 알려주기!
        // ===================================================
        if (orderPanel != null) {
            // OrderPanel의 setSelectedCoin 메서드를 호출해서 코인 변경 지시
            orderPanel.setSelectedCoin(coinSymbol);
        }
    }
    
    /**
     * 호가창 패널 업데이트
     */
    private void updateOrderBookPanel(String coinSymbol) {
        if (orderBookPanel != null) {
            orderBookPanel.closeConnection();
        }
        
        orderBookPanel = new OrderBookPanel(coinSymbol);
        orderBookPanel.setPreferredSize(new Dimension(0, 350));
        orderBookPanel.setBorder(BorderFactory.createTitledBorder(
            coinSymbol + " 호가창"
        ));
        
        // tradingPanel의 centerArea 찾아서 업데이트
        JPanel centerArea = (JPanel) ((JPanel) tradingPanel.getComponent(1));
        centerArea.remove(1); // 기존 호가창 제거
        centerArea.add(orderBookPanel, BorderLayout.SOUTH);
        
        centerArea.revalidate();
        centerArea.repaint();
    }
    
    /**
     * 웹소켓 초기화
     */
    private void initWebSocket() {
        if (timeController.isRealtimeMode()) {
            UpbitWebSocketDao.getInstance().start();
        }
    }
    
    /**
     * 시간 변경 이벤트 처리
     */
    @Override
    public void onTimeChanged(java.time.LocalDateTime newTime, boolean isRealtime) {
        SwingUtilities.invokeLater(() -> {
            if (isRealtime) {
                System.out.println("[MainFrame] 실시간 모드로 전환됨");
                // 차트의 backtestTargetTime 초기화 후 실시간 복귀
                chartPanel.resetToRealtimeMode();
                UpbitWebSocketDao.getInstance().start();
            } else {
                System.out.println("[MainFrame] 백테스팅 모드로 전환됨: " + newTime);
                UpbitWebSocketDao.getInstance().close();
                chartPanel.loadHistoricalData(newTime);
            }
        });
    }
    
    /**
     * 리소스 정리
     */
    @Override
    public void dispose() {
        UpbitWebSocketDao.getInstance().close();
        
        if (orderBookPanel != null) {
            orderBookPanel.closeConnection();
        }
        
        DBConnection.close();
        super.dispose();
    }
    
    
    public static void main(String[] args) {
        // UI 룩앤필 설정
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // UI 생성 및 실행
        SwingUtilities.invokeLater(() -> {
            MainFrame mainFrame = new MainFrame("test_user1");
            
            // UI를 띄운 직후, 백그라운드 스레드를 생성하여 데이터 업데이트 실행
            new Thread(() -> {
                System.out.println("백그라운드에서 캔들 데이터 동기화를 시작합니다...");
                
                // 필요한 경우에만 최초 1회 실행
                // DownloadDatabase.importData(1); 
                
                // 평상시: DB에 있는 최신 시간부터 현재까지만 업데이트
                DownloadDatabase.updateData(1); 
                
                System.out.println("데이터 동기화 완료!");
                
                // (선택) 업데이트가 완료된 후 MainFrame의 차트나 리스트를 새로고침하고 싶다면
                // 반드시 다시 SwingUtilities.invokeLater를 사용해서 UI를 건드려야 합니다.
                // SwingUtilities.invokeLater(() -> {
                //     mainFrame.refreshMarketData(); // MainFrame에 새로고침 메소드를 만들었다면 호출
                // });
                
            }).start(); // 스레드 시작
        });
    }
}