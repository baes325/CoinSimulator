package Investment_details;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import Investment_details.Asset.Asset_MainPanel;
import Investment_details.OpenOrder.OpenOrder_MainPanel;
import Investment_details.history.History_MainPanel;
import Investment_details.profitloss.ProfitLoss_MainPanel;

/**
 * 투자내역 통합 메인 패널
 *
 * 레이아웃:
 * ┌──────────────────────────────────────────────┐
 * │  Investment_details_NavigationPanel (NORTH)  │ ← 탭 버튼
 * ├──────────────────────────────────────────────┤
 * │  CardLayout 패널 (CENTER)                    │
 * │  ┌─────────────────────────────────┐        │
 * │  │ Asset_MainPanel (보유자산)       │        │
 * │  │ ProfitLoss_MainPanel (투자손익)  │        │
 * │  │ History_MainPanel (거래내역)     │        │
 * │  │ OpenOrder_MainPanel (미체결)     │        │
 * │  └─────────────────────────────────┘        │
 * └──────────────────────────────────────────────┘
 */
public class Investment_details_MainPanel extends JPanel {

    private Investment_details_NavigationPanel navPanel;

    private CardLayout cardLayout;
    private JPanel contentPanel;

    // 모든 하위 패널이 sessionId를 받도록 통일
    private Asset_MainPanel      assetPanel;
    private ProfitLoss_MainPanel profitLossPanel;
    private History_MainPanel    historyPanel;
    private OpenOrder_MainPanel  openOrderPanel;

    private long   sessionId;
    private final String userId;

    // 카드 식별자
    private static final String CARD_ASSET       = "ASSET";
    private static final String CARD_PROFIT_LOSS = "PROFIT_LOSS";
    private static final String CARD_HISTORY     = "HISTORY";
    private static final String CARD_OPEN_ORDER  = "OPEN_ORDER";

    public Investment_details_MainPanel(String userId, long sessionId) {
        this.userId    = userId;
        this.sessionId = sessionId;

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        navPanel = new Investment_details_NavigationPanel();

        cardLayout   = new CardLayout();
        contentPanel = new JPanel(cardLayout);
        contentPanel.setBackground(Color.WHITE);

        // 모든 하위 패널 생성 시 sessionId 전달
        assetPanel      = new Asset_MainPanel(userId, sessionId);
        profitLossPanel = new ProfitLoss_MainPanel(userId, sessionId);
        historyPanel    = new History_MainPanel(userId, sessionId);
        openOrderPanel  = new OpenOrder_MainPanel(userId, sessionId);

        contentPanel.add(assetPanel,      CARD_ASSET);
        contentPanel.add(profitLossPanel, CARD_PROFIT_LOSS);
        contentPanel.add(historyPanel,    CARD_HISTORY);
        contentPanel.add(openOrderPanel,  CARD_OPEN_ORDER);

        add(navPanel,     BorderLayout.NORTH);
        add(contentPanel, BorderLayout.CENTER);

        setupTabListeners();

        // 기본 화면: 보유자산
        cardLayout.show(contentPanel, CARD_ASSET);
    }

    // ── 탭 리스너 설정 ────────────────────────────────────────────

    private void setupTabListeners() {
        navPanel.addAssetTabListener(e -> {
            cardLayout.show(contentPanel, CARD_ASSET);
            assetPanel.initAssetData();
        });

        navPanel.addProfitLossTabListener(e -> {
            cardLayout.show(contentPanel, CARD_PROFIT_LOSS);
            profitLossPanel.refresh(30);
        });

        navPanel.addHistoryTabListener(e -> {
            cardLayout.show(contentPanel, CARD_HISTORY);
            historyPanel.refresh();
        });

        navPanel.addOpenOrderTabListener(e -> {
            cardLayout.show(contentPanel, CARD_OPEN_ORDER);
            openOrderPanel.refresh();
        });
    }

    // ── 외부 API ─────────────────────────────────────────────────

    /** 전체 데이터 새로고침 (주문 체결 후 등) */
    public void refreshAll() {
        assetPanel.initAssetData();
        profitLossPanel.refresh(30);
        historyPanel.refresh();
        openOrderPanel.refresh();
    }

    /**
     * 세션 변경 시 호출 — 모든 하위 패널에 새 sessionId 전달 후 재조회
     *
     * @param newSessionId 새 세션 ID
     */
    public void setSessionId(long newSessionId) {
        this.sessionId = newSessionId;

        // 모든 하위 패널에 새 세션 ID 전파 및 즉시 새로고침 (두 브랜치의 장점 완벽 병합!)
        if (assetPanel != null) { 
            assetPanel.setSessionId(newSessionId); 
            assetPanel.initAssetData(); 
        }
        if (profitLossPanel != null) { 
            profitLossPanel.setSessionId(newSessionId); 
            profitLossPanel.refresh(30); 
        }
        if (historyPanel != null) { 
            historyPanel.setSessionId(newSessionId); 
            historyPanel.refresh(); 
        }
        if (openOrderPanel != null) { 
            openOrderPanel.setSessionId(newSessionId); 
            openOrderPanel.refresh(); 
        }
    }

    // ── 독립 실행 테스트 ──────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("투자내역 통합 패널 테스트");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1200, 800);
            frame.setLocationRelativeTo(null);

            long dummySessionId = 1L;
            Investment_details_MainPanel panel =
                new Investment_details_MainPanel("user_01", dummySessionId);
            frame.add(panel);
            frame.setVisible(true);

            DAO.UpbitWebSocketDao.getInstance().start();
        });
    }
}