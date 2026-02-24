package Investment_details.Asset;

import DAO.AssetDAO;
import DAO.UpbitWebSocketDao;
import DTO.AssetDTO;
import DTO.MyAssetStatusDTO;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 보유자산 메인 패널 (업비트 스타일)
 *
 * 레이아웃:
 * ┌─────────────────────────────────────────────┐
 * │  Asset_SummaryPanel  (NORTH - 상단 요약)     │
 * ├─────────────────────────────────────────────┤
 * │  Assets_TablePanel   (CENTER - 보유목록)     │
 * └─────────────────────────────────────────────┘
 */
public class Asset_MainPanel extends JPanel implements UpbitWebSocketDao.TickerListener {

    private final Asset_SummaryPanel summaryPanel;
    private final Assets_TablePanel  tablePanel;

    private final AssetDAO assetDAO;
    private final String   userId;
    private long sessionId;

    private List<MyAssetStatusDTO> myAssetList = new ArrayList<>();
    private BigDecimal             krwBalance  = BigDecimal.ZERO;

    public Asset_MainPanel(String userId, long sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.assetDAO = new AssetDAO();

        setLayout(new BorderLayout(0, 0));
        setBackground(Color.WHITE);

        summaryPanel = new Asset_SummaryPanel();
        tablePanel   = new Assets_TablePanel();

        add(summaryPanel, BorderLayout.NORTH);
        add(tablePanel,   BorderLayout.CENTER);

        // 웹소켓 리스너 등록
        UpbitWebSocketDao.getInstance().addListener(this);

        // 초기 DB 데이터 로드
        initAssetData();
    }
    // 세션 ID 업데이트 메서드 추가
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
    }

    // ── 초기 데이터 로드 ──────────────────────────────────────────
    public void initAssetData() {
        myAssetList.clear();
        krwBalance = BigDecimal.ZERO;

        List<AssetDTO> dbAssets = assetDAO.getAllAssets(userId, sessionId);

        for (AssetDTO asset : dbAssets) {
            if ("KRW".equalsIgnoreCase(asset.getCurrency())) {
                krwBalance = asset.getTotalAmount();
                continue;
            }

            MyAssetStatusDTO dto = new MyAssetStatusDTO();
            dto.setCurrency(asset.getCurrency());
            dto.setBalance(asset.getTotalAmount());
            dto.setAvgPrice(asset.getAvgBuyPrice() != null ? asset.getAvgBuyPrice() : BigDecimal.ZERO);

            // 초기 현재가 = 평단가 (웹소켓으로 갱신 전)
            BigDecimal initPrice = asset.getAvgBuyPrice() != null ? asset.getAvgBuyPrice() : BigDecimal.ZERO;
            dto.setCurrentPrice(initPrice);
            dto.setTotalValue(dto.getBalance().multiply(initPrice));

            myAssetList.add(dto);
        }

        refreshUI();
    }

    
    // ── 웹소켓 콜백 ───────────────────────────────────────────────
    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr) {
        boolean related = myAssetList.stream().anyMatch(d -> d.getCurrency().equals(symbol));
        if (related) {
            SwingUtilities.invokeLater(this::refreshUI);
        }
    }

    // ── UI 전체 갱신 ──────────────────────────────────────────────
    private void refreshUI() {
        BigDecimal totalEval = BigDecimal.ZERO;
        BigDecimal totalBuy  = BigDecimal.ZERO;

        for (MyAssetStatusDTO dto : myAssetList) {
            // 최신 현재가
            String market = "KRW-" + dto.getCurrency();
            BigDecimal cur = UpbitWebSocketDao.getCurrentPrice(market);
            if (cur != null && cur.compareTo(BigDecimal.ZERO) > 0) {
                dto.setCurrentPrice(cur);
            }

            BigDecimal eval = dto.getBalance().multiply(dto.getCurrentPrice());
            BigDecimal buy  = dto.getBalance().multiply(dto.getAvgPrice());
            dto.setTotalValue(eval);

            // 수익률
            if (dto.getAvgPrice().compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal diff = dto.getCurrentPrice().subtract(dto.getAvgPrice());
                double rate = diff.divide(dto.getAvgPrice(), 6, RoundingMode.HALF_UP).doubleValue() * 100;
                dto.setProfitRate(rate);
            }

            totalEval = totalEval.add(eval);
            totalBuy  = totalBuy.add(buy);
        }

        BigDecimal totalAsset = krwBalance.add(totalEval);
        BigDecimal totalPnl   = totalEval.subtract(totalBuy);

        double totalYield = 0.0;
        if (totalBuy.compareTo(BigDecimal.ZERO) > 0) {
            totalYield = totalPnl.divide(totalBuy, 6, RoundingMode.HALF_UP).doubleValue() * 100;
        }

        // 각 패널 갱신
        summaryPanel.updateSummary(
            krwBalance,  // 보유 KRW
            totalAsset,  // 총 보유자산
            totalBuy,    // 총 매수
            totalEval,   // 총 평가
            krwBalance,  // 주문가능 (= KRW)
            totalPnl,    // 총평가손익
            totalYield   // 총평가수익률
        );
        summaryPanel.updateChart(myAssetList, krwBalance);
        tablePanel.updateTable(myAssetList);

        revalidate();
        repaint();
    }

    // ── 독립 테스트 ───────────────────────────────────────────────
//    public static void main(String[] args) {
//        SwingUtilities.invokeLater(() -> {
//            JFrame frame = new JFrame("보유자산 테스트");
//            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//            frame.setSize(1100, 700);
//            frame.setLocationRelativeTo(null);
//
//            Asset_MainPanel panel = new Asset_MainPanel("user_01");
//            frame.add(panel);
//            frame.setVisible(true);
//
//            UpbitWebSocketDao.getInstance().start();
//        });
//    }
}