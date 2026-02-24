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
    // =========================
    private JPanel tradingPanel;
    private HistoryPanel historyPanel;
    private CandleChartPanel chartPanel;
    private OrderBookPanel orderBookPanel;
    private OrderPanel orderPanel;

    // 투자내역 화면 컴포넌트
    private Investment_details_MainPanel investmentPanel;

    // 상태 관리
    private String currentUserId = "test_user1";
    private boolean isTradingView = true;

    // 카드 식별자
    private static final String CARD_TRADING   = "TRADING";
    private static final String CARD_INVESTMENT = "INVESTMENT";

    // 알림 감시자
    private PriceAlertService alertService;

    // 백테스팅 UI / 어댑터
    private BacktestTimeControlPanel backtestControlPanel;
    private CandleChartBacktestAdapter chartBacktestAdapter;


    private long currentSessionId = SessionManager.getInstance().getCurrentSessionId();


    // ════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════

    public MainFrame(String userId) {
        super("가상화폐 모의투자 시스템");
        this.currentUserId = (userId == null) ? "" : userId.trim();
        System.out.println("🔥 currentUserId = [" + this.currentUserId + "]");

        // TimeController 초기화
        timeController = TimeController.getInstance();
        timeController.initialize(currentUserId);
        timeController.addTimeChangeListener(this);

        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1600, 900);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());

        // 프로필 먼저 로드
        loadProfileFromDb();

        // UI 구성
        initComponents();

        // 웹소켓
        initWebSocket();
        alertService = new PriceAlertService(this);
        setVisible(true);
        syncMarketDataBackground();
    }

    // ════════════════════════════════════════════════
    //  초기화
    // ════════════════════════════════════════════════

    private void syncMarketDataBackground() {
        new Thread(() -> {
            System.out.println("[MainFrame] 백그라운드에서 캔들 데이터 동기화를 시작합니다...");
            try {
                DownloadDatabase.updateData(1);
                System.out.println("[MainFrame] 데이터 동기화 완료!");

                SwingUtilities.invokeLater(() -> {
                    if (investmentPanel != null) {
                        investmentPanel.setSessionId(currentSessionId);
                        investmentPanel.refreshAll();
                    }
                    System.out.println("[MainFrame] UI 데이터 갱신 완료");
                });
            } catch (Exception e) {
                System.err.println("[MainFrame] 데이터 동기화 중 오류 발생: " + e.getMessage());
                e.printStackTrace();
            }
        }).start();
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

        chartBacktestAdapter = new CandleChartBacktestAdapter(chartPanel, historyPanel);
        BacktestTimeController.getInstance().addTickListener(chartBacktestAdapter);

        mainContentPanel.add(tradingPanel,    CARD_TRADING);
        mainContentPanel.add(investmentPanel, CARD_INVESTMENT);

        add(mainContentPanel, BorderLayout.CENTER);
        mainCardLayout.show(mainContentPanel, CARD_TRADING);
    }

    // =========================
    // 상단바
    // =========================
    private JPanel createTopPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(Color.WHITE);
        panel.setPreferredSize(new Dimension(0, TOP_BAR_HEIGHT));

        // 가운데 시간 컨트롤
        timeControlPanel = new TimeControlPanel();
        panel.add(timeControlPanel, BorderLayout.CENTER);

        // 오른쪽: [투자내역 버튼] + [프로필]
        JPanel rightPanel = new JPanel();
        rightPanel.setBackground(Color.WHITE);
        rightPanel.setLayout(new BoxLayout(rightPanel, BoxLayout.X_AXIS));
        rightPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 12));

        // 투자내역 보기 버튼
        btnToggleView = new JButton("투자내역 보기");
        btnToggleView.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btnToggleView.setForeground(Color.WHITE);
        btnToggleView.setBackground(new Color(52, 152, 219));
        btnToggleView.setFocusPainted(false);
        btnToggleView.setBorderPainted(false);
        btnToggleView.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        btnToggleView.setPreferredSize(new Dimension(BTN_WIDTH, BTN_HEIGHT));
        btnToggleView.setMinimumSize(new Dimension(BTN_WIDTH, BTN_HEIGHT));
        btnToggleView.setMaximumSize(new Dimension(BTN_WIDTH, BTN_HEIGHT));
        btnToggleView.setAlignmentY(Component.CENTER_ALIGNMENT);

        btnToggleView.addMouseListener(new MouseAdapter() {
            public void mouseEntered(MouseEvent evt) {
                btnToggleView.setBackground(new Color(41, 128, 185));
            }
            public void mouseExited(MouseEvent evt) {
                btnToggleView.setBackground(isTradingView ? new Color(52, 152, 219) : new Color(46, 204, 113));
            }
        });

        btnToggleView.addActionListener(e -> toggleView());

        rightPanel.add(btnToggleView);
        rightPanel.add(Box.createHorizontalStrut(12));

        // 프로필 아이콘
        profileIconLabel = new JLabel();
        profileIconLabel.setPreferredSize(new Dimension(PROFILE_SIZE, PROFILE_SIZE));
        profileIconLabel.setMinimumSize(new Dimension(PROFILE_SIZE, PROFILE_SIZE));
        profileIconLabel.setMaximumSize(new Dimension(PROFILE_SIZE, PROFILE_SIZE));
        profileIconLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        profileIconLabel.setAlignmentY(Component.CENTER_ALIGNMENT);

        setProfileIcon(currentProfileImagePath);

        profileIconLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                showProfileMenu(profileIconLabel, 0, profileIconLabel.getHeight());
            }
        });

        rightPanel.add(profileIconLabel);

        panel.add(rightPanel, BorderLayout.EAST);
        return panel;
    }

    // =========================
    // 프로필 로드/표시/메뉴
    // =========================
    private void loadProfileFromDb() {
        UserDAO.ProfileInfo info = UserDAO.getProfile(currentUserId);
        if (info != null) {
            currentNickname = info.nickname;
            currentProfileImagePath = info.imagePath;
        }
    }

    private ImageIcon loadDefaultAvatar(int size) {
        try {
            java.net.URL url = getClass().getResource("/assets/default_avatar.png");
            System.out.println("[DEBUG] default avatar url = " + url);
            if (url == null) return null;

            ImageIcon icon = new ImageIcon(url);
            Image img = icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
            return new ImageIcon(img);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private ImageIcon makeCircleIcon(ImageIcon src, int size) {
        if (src == null || src.getImage() == null) {
            src = (ImageIcon) UIManager.getIcon("OptionPane.questionIcon");
        }

        Image img = src.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);

        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setClip(new Ellipse2D.Float(0, 0, size, size));
        g2.drawImage(img, 0, 0, null);

        g2.setClip(null);
        g2.setStroke(new BasicStroke(1f));
        g2.setColor(new Color(220, 220, 220));
        g2.drawOval(0, 0, size - 1, size - 1);

        g2.dispose();
        return new ImageIcon(out);
    }

    private void setProfileIcon(String path) {
        ImageIcon iconToUse = null;

        try {
            if (path != null && !path.isBlank()) {
                File f = new File(path);
                if (f.exists()) {
                    // 캐시 느낌 방지: ImageIO로 강제 로드
                    BufferedImage bi = ImageIO.read(f);
                    iconToUse = new ImageIcon(bi);
                }
            }
        } catch (Exception ignore) {}

        if (iconToUse == null) {
            iconToUse = loadDefaultAvatar(PROFILE_SIZE);
        }

        if (iconToUse == null) {
            iconToUse = (ImageIcon) UIManager.getIcon("OptionPane.questionIcon");
        }

        profileIconLabel.setIcon(makeCircleIcon(iconToUse, PROFILE_SIZE));
    }

    private void showProfileMenu(Component comp, int x, int y) {
        JPopupMenu menu = new JPopupMenu();

        String nick = (currentNickname == null || currentNickname.isBlank()) ? "(닉네임 없음)" : currentNickname;

        JMenuItem info = new JMenuItem("ID: " + currentUserId + " / " + nick);
        info.setEnabled(false);
        menu.add(info);
        menu.addSeparator();

        JMenuItem editNick = new JMenuItem("닉네임 변경");
        editNick.addActionListener(e -> changeNickname());
        menu.add(editNick);

        JMenuItem uploadPhoto = new JMenuItem("사진 등록/변경");
        uploadPhoto.addActionListener(e -> changeProfilePhoto());
        menu.add(uploadPhoto);

        menu.addSeparator();

        JMenuItem changePw = new JMenuItem("비밀번호 변경");
        changePw.addActionListener(e -> openChangePassword());
        menu.add(changePw);

        JMenuItem logout = new JMenuItem("로그아웃");
        logout.addActionListener(e -> {
            dispose();
            SwingUtilities.invokeLater(LoginFrame::new);
        });
        menu.addSeparator();
        menu.add(logout);

        menu.show(comp, x, y);
    }

    // =========================
    // 닉네임 변경
    // =========================
    private void changeNickname() {
        String input = JOptionPane.showInputDialog(this, "새 닉네임을 입력하세요.",
                currentNickname == null ? "" : currentNickname);
        if (input == null) return;

        String newNick = input.trim();
        if (newNick.isEmpty()) return;

        String err = UserDAO.updateNicknameWithError(currentUserId, newNick);
        if (err != null) {
            JOptionPane.showMessageDialog(this, "닉네임 변경 실패:\n" + err, "오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        currentNickname = newNick;
        JOptionPane.showMessageDialog(this, "닉네임이 변경되었습니다.");
    }

    /**
     * ✅ 안정적인 사진 등록/변경(완전 단일 구현)
     *  - 사용자가 고른 파일을 실행 폴더의 profiles/로 복사
     *  - DB에는 복사된 절대경로 저장
     *  - UI는 ImageIO 강제 로드로 즉시 반영
     *  - 알람은 1번만
     */
    private void changeProfilePhoto() {

        FileDialog fd = new FileDialog(this, "프로필 사진 선택", FileDialog.LOAD);
        fd.setDirectory(System.getProperty("user.home") + File.separator + "Pictures");
        fd.setFilenameFilter((dir, name) -> {
            String n = name.toLowerCase();
            return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg")
                    || n.endsWith(".gif") || n.endsWith(".bmp") || n.endsWith(".webp");
        });
        fd.setVisible(true);

        String file = fd.getFile();
        String dir = fd.getDirectory();
        if (file == null || dir == null) return;

        File src = new File(dir, file);
        if (!src.exists()) {
            JOptionPane.showMessageDialog(this, "선택한 파일이 존재하지 않습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        try {
            Path profilesDir = Path.of("profiles");
            Files.createDirectories(profilesDir);

            String name = src.getName();
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot >= 0) ext = name.substring(dot);

            String safeUser = currentUserId.replaceAll("[^a-zA-Z0-9._-]", "_");
            String stamp = String.valueOf(System.currentTimeMillis());

            // ✅ 매번 다른 파일명 (덮어쓰기/캐시 느낌 방지)
            Path dst = profilesDir.resolve("profile_" + safeUser + "_" + stamp + ext);

            Files.copy(src.toPath(), dst, StandardCopyOption.REPLACE_EXISTING);

            String savedPath = dst.toAbsolutePath().toString();

            // ✅ DB 저장 (성공이면 null)
            String err = UserDAO.updateProfileImagePathWithError(currentUserId, savedPath);
            if (err != null) {
                JOptionPane.showMessageDialog(this, "DB 저장 실패:\n" + err, "오류", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ✅ 상태 갱신 + UI 즉시 반영(강제 로드)
            currentProfileImagePath = savedPath;
            BufferedImage bi = ImageIO.read(new File(savedPath));
            ImageIcon fresh = new ImageIcon(bi);
            profileIconLabel.setIcon(makeCircleIcon(fresh, PROFILE_SIZE));
            profileIconLabel.revalidate();
            profileIconLabel.repaint();

            // ✅ 알람 1번만
            JOptionPane.showMessageDialog(this, "프로필 사진이 변경되었습니다.");

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(this, "프로필 사진 등록 중 오류:\n" + ex.getMessage(),
                    "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void openChangePassword() {
        new ChangePasswordFrame(currentUserId);
    }

    // =========================
    // 거래 패널
    // =========================
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

        orderPanel = new OrderPanel(currentUserId);
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

        // 백테스팅 엔진 시작
        BacktestTimeController.getInstance().startSession(
                currentUserId, currentSessionId, startTime, currentSimTime, endTime);

        if (backtestControlPanel != null) {
            backtestControlPanel.activateSessionUI(selectedSession);
        }
    }

    /**
     * 백테스팅 → 실시간 복귀 시 BacktestTimeControlPanel이 호출합니다.
     * (구 TimeController.switchToRealtimeMode() 역할)
     */
    public void returnToRealtimeMode() {
        SwingUtilities.invokeLater(() -> {
            System.out.println("[MainFrame] 실시간 모드로 전환됨");
            this.currentSessionId = 1L;
            
            if (investmentPanel != null) investmentPanel.setSessionId(1L);
            if (historyPanel != null) historyPanel.setSessionId(1L);
            //백->실시간 돌아올 때
            if (orderPanel != null) {
                orderPanel.setSessionId(1L); 
            }
            chartPanel.resetToRealtimeMode();
            UpbitWebSocketDao.getInstance().start();
        });
    }

    // ════════════════════════════════════════════════
    //  화면 전환 / 이벤트 핸들러
    // ════════════════════════════════════════════════

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

        chartPanel.changeMarket(coinSymbol);
        updateOrderBookPanel(coinSymbol);

        if (orderPanel != null) {
            orderPanel.setSelectedCoin(coinSymbol);
        }
    }

    private void updateOrderBookPanel(String coinSymbol) {
        if (orderBookPanel != null) orderBookPanel.closeConnection();

        orderBookPanel = new OrderBookPanel(coinSymbol);
        orderBookPanel.setPreferredSize(new Dimension(0, 350));
        orderBookPanel.setBorder(BorderFactory.createTitledBorder(coinSymbol + " 호가창"));

        JPanel centerArea = (JPanel) ((JPanel) tradingPanel.getComponent(1));
        centerArea.remove(1);
        centerArea.add(orderBookPanel, BorderLayout.SOUTH);
        centerArea.revalidate();
        centerArea.repaint();
    }

    // ════════════════════════════════════════════════
    //  WebSocket / 리소스
    // ════════════════════════════════════════════════

    private void initWebSocket() {
        UpbitWebSocketDao.getInstance().start();
    }

    @Override
    public void dispose() {
        UpbitWebSocketDao.getInstance().close();
        if (orderBookPanel != null) orderBookPanel.closeConnection();
        DBConnection.close();
        super.dispose();
    }

    // ════════════════════════════════════════════════
    //  진입점
    // ════════════════════════════════════════════════

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            e.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> new MainFrame("test_user1"));
    }
}