package Investment_details.profitloss;

import DTO.ExecutionDTO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;

import DAO.ProfitLossDAO;

import java.awt.*;
import java.util.List;

/**
 * 투자손익 메인 패널
 *
 * 레이아웃:
 * ┌─────────────────────────────────────────┐
 * │  ProfitLoss_PeriodSelector    (TOP)     │
 * ├─────────────────────────────────────────┤
 * │  ProfitLoss_SummaryStatPanel  (NORTH)   │
 * ├─────────────────────────────────────────┤
 * │  ProfitLoss_ChartAreaPanel    (CENTER)  │
 * ├─────────────────────────────────────────┤
 * │  ProfitLoss_DetailTablePanel  (SOUTH)   │
 * └─────────────────────────────────────────┘
 */
public class ProfitLoss_MainPanel extends JPanel {

    private final ProfitLoss_PeriodSelector    periodSelector;
    private final ProfitLoss_SummaryStatPanel  summaryPanel;
    private final ProfitLoss_ChartAreaPanel    chartAreaPanel;
    private final ProfitLoss_DetailTablePanel  tablePanel;

    private final ProfitLossDAO dao;
    private final String userId;
    private long sessionId; // [핵심] 세션 필터 기준

    private List<ExecutionDTO> currentExecutions;
    private int currentDays = 30;

    public ProfitLoss_MainPanel(String userId, long sessionId) {
        this.userId    = userId;
        this.sessionId = sessionId;
        this.dao       = new ProfitLossDAO();

        setLayout(new BorderLayout(0, 0));
        setBackground(Color.WHITE);

        periodSelector = new ProfitLoss_PeriodSelector();
        summaryPanel   = new ProfitLoss_SummaryStatPanel();
        chartAreaPanel = new ProfitLoss_ChartAreaPanel();
        tablePanel     = new ProfitLoss_DetailTablePanel();

        // 기간 선택 리스너
        periodSelector.addPeriodChangeListener(days -> {
            currentDays = days;
            loadRecentData(days);
        });

        // 레이아웃 조립
        JPanel periodWrapper = new JPanel(new BorderLayout());
        periodWrapper.setBackground(Color.WHITE);
        periodWrapper.setBorder(new MatteBorder(0, 0, 1, 0, new Color(230, 230, 230)));
        periodWrapper.add(periodSelector, BorderLayout.CENTER);

        JPanel summaryChartPanel = new JPanel(new BorderLayout(0, 0));
        summaryChartPanel.setBackground(Color.WHITE);
        summaryChartPanel.setBorder(new EmptyBorder(0, 0, 4, 0));
        summaryChartPanel.add(summaryPanel,   BorderLayout.NORTH);
        summaryChartPanel.add(chartAreaPanel, BorderLayout.CENTER);

        JPanel topContainer = new JPanel(new BorderLayout(0, 0));
        topContainer.setBackground(Color.WHITE);
        topContainer.add(periodWrapper,   BorderLayout.NORTH);
        topContainer.add(summaryChartPanel, BorderLayout.CENTER);

        tablePanel.setPreferredSize(new Dimension(0, 260));

        add(topContainer, BorderLayout.CENTER);
        add(tablePanel,   BorderLayout.SOUTH);

        loadRecentData(30);
    }

    // ── 데이터 로드 ───────────────────────────────────────────────

    /**
     * 최근 N일간 체결 데이터를 DB에서 조회하여 화면에 표시
     * [수정] getSellExecutions -> getExecutions 로 변경하여 매수(수수료 포함) 내역까지 로드
     *
     * @param days 조회할 일수
     */
    public void loadRecentData(int days) {
        this.currentExecutions = dao.getExecutions(userId, sessionId, days); 
        refreshAll();
    }

    /** 외부에서 데이터를 직접 설정 (테스트용) */
    public void loadData(List<ExecutionDTO> executions) {
        this.currentExecutions = executions;
        refreshAll();
    }

    // ── UI 갱신 ───────────────────────────────────────────────────

    private void refreshAll() {
        // 초기 자본금 (수익률 계산의 기준점)
        long initialSeedMoney = dao.getInitialSeedMoney(userId, sessionId); 

        if (currentExecutions == null || currentExecutions.isEmpty()) {
            summaryPanel.updateSummary(0, 0.0, initialSeedMoney, 0); 
            chartAreaPanel.updateCharts(currentExecutions, userId, sessionId);
            tablePanel.updateTable(currentExecutions, userId);
            return;
        }

        long periodRealizedPnl = 0; // 매도 실현 손익 (매도 수수료 기 차감됨)
        long buyFeeTotal = 0;       // 매수 수수료 합산
        long totalFee = 0;          // 전체 수수료 합산 (단순 표시용)

        for (ExecutionDTO exec : currentExecutions) {
            // 1. 전체 수수료 합산 (화면 표시용)
            if (exec.getFee() != null) {
                totalFee += exec.getFee().longValue();
            }
            
            if ("ASK".equals(exec.getSide())) {
                // 2. 매도 체결 시: DB에 저장된 실현 손익(매도 수수료가 이미 빠진 금액) 합산
                if (exec.getRealizedPnl() != null) {
                    periodRealizedPnl += exec.getRealizedPnl().longValue();
                }
            } else if ("BID".equals(exec.getSide())) {
                // 3. 매수 체결 시: 실현 손익에는 안 들어가지만, 내 잔고에서 수수료가 빠져나갔으므로 따로 모아둠
                if (exec.getFee() != null) {
                    buyFeeTotal += exec.getFee().longValue();
                }
            }
        }

        // 올바른 순손익 계산: 매도수익(매도수수료 차감완료) - 매수수수료
        long periodNetPnl = periodRealizedPnl - buyFeeTotal;

        // 기간 수익률 = (해당 기간 순손익 / 초기 자본금) * 100
        double periodYield = initialSeedMoney > 0
            ? ((double) periodNetPnl / initialSeedMoney) * 100.0
            : 0.0;

        // 평균 투자금액 (단순 참고용 유지)
        long avgInvestment = initialSeedMoney + (periodNetPnl / 2);

        // UI 갱신 (화면에는 전체 수수료 totalFee를 보여줌)
        summaryPanel.updateSummary(periodNetPnl, periodYield, avgInvestment, totalFee);
        chartAreaPanel.updateCharts(currentExecutions, userId, sessionId);
        tablePanel.updateTable(currentExecutions, userId);

        revalidate();
        repaint();
    }

    // ── 외부 API ─────────────────────────────────────────────────

    /** 외부에서 데이터 새로고침 */
    public void refresh() {
        loadRecentData(currentDays);
    }

    /** 외부에서 데이터 새로고침 (기존 메서드) */
    public void refresh(int days) {
        loadRecentData(days);
    }

    /** 세션 변경 시 호출 — 새 세션의 투자손익을 다시 로드 */
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        loadRecentData(currentDays); // 현재 기간 유지하면서 세션만 교체
    }
}