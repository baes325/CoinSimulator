package Investment_details.OpenOrder;

import java.awt.BorderLayout;
import java.awt.Color;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import DAO.OpenOrderDAO;
import DTO.OrderDTO;

/**
 * 미체결 주문 메인 패널
 *
 * 레이아웃:
 * ┌─────────────────────────────────────────────┐
 * │  OpenOrder_TopControlPanel (NORTH - 필터)    │
 * ├─────────────────────────────────────────────┤
 * │  OpenOrder_CenterDisplayPanel (CENTER - 테이블)│
 * └─────────────────────────────────────────────┘
 */
public class OpenOrder_MainPanel extends JPanel {

    private OpenOrder_TopControlPanel topPanel;
    private OpenOrder_CenterDisplayPanel tablePanel;

    private final OpenOrderDAO dao;
    private final String userId;
    private long sessionId; // [핵심] 세션 필터 기준

    private String currentMarket = "ALL";

    public OpenOrder_MainPanel(String userId, long sessionId) {
        this.userId    = userId;
        this.sessionId = sessionId;
        this.dao       = new OpenOrderDAO();

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        topPanel   = new OpenOrder_TopControlPanel();
        tablePanel = new OpenOrder_CenterDisplayPanel();

        add(topPanel,   BorderLayout.NORTH);
        add(tablePanel, BorderLayout.CENTER);

        setupListeners();
        refreshData();
    }

    // ── 이벤트 리스너 설정 ────────────────────────────────────────

    private void setupListeners() {
        topPanel.addMarketFilterListener(e -> {
            currentMarket = topPanel.getSelectedMarket();
            refreshData();
        });

        topPanel.addCancelAllListener(e -> handleCancelAll());
        tablePanel.setCancelOrderListener(this::handleCancelOrder);
    }

    // ── 데이터 새로고침 ───────────────────────────────────────────

    /** [핵심] sessionId를 DAO에 전달하여 해당 세션의 미체결 주문만 조회 */
    private void refreshData() {
        List<OrderDTO> orders;

        if ("ALL".equals(currentMarket)) {
            orders = dao.getOpenOrders(userId, sessionId);           // sessionId 적용
        } else {
            orders = dao.getOpenOrdersByMarket(userId, sessionId, currentMarket); // sessionId 적용
        }

        tablePanel.updateData(orders);
    }

    // ── 주문 취소 처리 ────────────────────────────────────────────

    private void handleCancelOrder(long orderId) {
        int confirm = JOptionPane.showConfirmDialog(
            this, "이 주문을 취소하시겠습니까?",
            "주문 취소 확인", JOptionPane.YES_NO_OPTION);

        if (confirm == JOptionPane.YES_OPTION) {
            boolean success = dao.cancelOrder(orderId);
            if (success) {
                JOptionPane.showMessageDialog(this,
                    "주문이 취소되었습니다.", "취소 완료", JOptionPane.INFORMATION_MESSAGE);
                refreshData();
            } else {
                JOptionPane.showMessageDialog(this,
                    "주문 취소에 실패했습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void handleCancelAll() {
        int confirm = JOptionPane.showConfirmDialog(
            this, "모든 미체결 주문을 취소하시겠습니까?",
            "일괄 취소 확인", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            int canceledCount = dao.cancelAllOrders(userId, sessionId); // sessionId 적용
            if (canceledCount > 0) {
                JOptionPane.showMessageDialog(this,
                    canceledCount + "개의 주문이 취소되었습니다.", "취소 완료", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JOptionPane.showMessageDialog(this,
                    "취소할 주문이 없습니다.", "알림", JOptionPane.INFORMATION_MESSAGE);
            }
            refreshData();
        }
    }

    // ── 외부 API ─────────────────────────────────────────────────

    /** 외부에서 데이터 새로고침 (주문 체결 후 등) */
    public void refresh() {
        refreshData();
    }

    /** 세션 변경 시 호출 — 새 세션의 미체결 주문을 다시 로드 */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        refreshData(); // 세션 변경 즉시 재조회
    }

    // ── 독립 실행 테스트 ──────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("미체결 주문 테스트");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(900, 600);
            frame.setLocationRelativeTo(null);

            OpenOrder_MainPanel panel = new OpenOrder_MainPanel("user_01", 1L);
            frame.add(panel);
            frame.setVisible(true);
        });
    }
}