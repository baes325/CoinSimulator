package Investment_details.history;

import java.awt.BorderLayout;
import java.awt.Color;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import DAO.HistoryDAO;
import DTO.ExecutionDTO;

/**
 * 거래내역 메인 패널
 *
 * 레이아웃:
 * ┌─────────────────────────────────────────┐
 * │  History_FilterContainerPanel  (NORTH)  │
 * ├─────────────────────────────────────────┤
 * │  History_Table_HistoryTablePanel        │
 * │  (CENTER)                               │
 * └─────────────────────────────────────────┘
 */
public class History_MainPanel extends JPanel {

    private final History_FilterContainerPanel filterPanel;
    private final History_Table_HistoryTablePanel tablePanel;

    private final HistoryDAO dao;
    private final String userId;
    private long sessionId; // [핵심] 세션 필터 기준

    // 현재 필터 상태
    private LocalDate currentStartDate;
    private LocalDate currentEndDate;
    private String currentType   = "ALL";
    private String currentMarket = "ALL";

    public History_MainPanel(String userId, long sessionId) {
        this.userId    = userId;
        this.sessionId = sessionId;
        this.dao       = new HistoryDAO();

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        filterPanel = new History_FilterContainerPanel();
        tablePanel  = new History_Table_HistoryTablePanel();

        add(filterPanel, BorderLayout.NORTH);
        add(tablePanel,  BorderLayout.CENTER);

        setupListeners();
        loadRecentMonth();
    }

    // ── 이벤트 리스너 설정 ────────────────────────────────────────

    private void setupListeners() {
        filterPanel.getPeriodPanel().addSearchListener(e -> {
            currentStartDate = filterPanel.getPeriodPanel().getStartDate();
            currentEndDate   = filterPanel.getPeriodPanel().getEndDate();
            refreshData();
        });

        filterPanel.getTypePanel().addFilterChangeListener(e -> {
            currentType = filterPanel.getTypePanel().getSelectedType();
            refreshData();
        });

        filterPanel.getSearchPanel().addFilterChangeListener(e -> {
            currentMarket = filterPanel.getSearchPanel().getSelectedMarket();
            refreshData();
        });
    }

    // ── 데이터 로드 ───────────────────────────────────────────────

    private void loadRecentMonth() {
        currentEndDate   = LocalDate.now();
        currentStartDate = currentEndDate.minusMonths(1);
        refreshData();
    }

    /** [핵심] sessionId를 DAO에 전달하여 해당 세션의 거래내역만 조회 */
    private void refreshData() {
        if (currentStartDate == null || currentEndDate == null) return;

        Date sqlStart = Date.valueOf(currentStartDate);
        Date sqlEnd   = Date.valueOf(currentEndDate);

        List<ExecutionDTO> executions;

        boolean noType   = "ALL".equals(currentType);
        boolean noMarket = "ALL".equals(currentMarket);

        if (noType && noMarket) {
            executions = dao.getExecutionHistory(userId, sessionId, sqlStart, sqlEnd);
        } else if (noMarket) {
            String side = noType ? null : currentType;
            executions = dao.getExecutionHistoryBySide(userId, sessionId, sqlStart, sqlEnd, side);
        } else if (noType) {
            executions = dao.getExecutionHistoryFiltered(
                userId, sessionId, sqlStart, sqlEnd, currentMarket, null);
        } else {
            executions = dao.getExecutionHistoryFiltered(
                userId, sessionId, sqlStart, sqlEnd, currentMarket, currentType);
        }

        tablePanel.updateData(executions);
    }

    // ── 외부 API ─────────────────────────────────────────────────

    /** 외부에서 데이터 새로고침 */
    public void refresh() {
        refreshData();
    }

    /** 특정 기간 데이터 로드 */
    public void loadPeriod(LocalDate startDate, LocalDate endDate) {
        this.currentStartDate = startDate;
        this.currentEndDate   = endDate;
        refreshData();
    }

    /** 세션 변경 시 호출 — 새 세션의 거래내역을 다시 로드 */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        loadRecentMonth(); // 세션 변경 시 기간 초기화 후 재조회
    }

    // ── 독립 실행 테스트 ──────────────────────────────────────────

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("거래내역 테스트");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1100, 700);
            frame.setLocationRelativeTo(null);
            
            History_MainPanel panel = new History_MainPanel("user_01", 1L); // 💡 테스트용 기본값 1L
            frame.add(panel);
            frame.setVisible(true);
        });
    }
}