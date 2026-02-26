package Investment_details.Asset;

import DAO.AssetDAO;
import DAO.HistoricalDataDAO;
import DAO.UpbitWebSocketDao;
import DTO.AssetDTO;
import DTO.MyAssetStatusDTO;
import DTO.TickerDto;
import com.team.coin_simulator.backtest.BacktestSpeed;
import com.team.coin_simulator.backtest.BacktestTimeController;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 보유자산 메인 패널 (업비트 스타일)
 * 실시간 웹소켓 및 백테스트 시간 엔진 연동
 */
public class Asset_MainPanel extends JPanel implements UpbitWebSocketDao.TickerListener, BacktestTimeController.BacktestTickListener {

    private final Asset_SummaryPanel summaryPanel;
    private final Assets_TablePanel  tablePanel;

    private final AssetDAO assetDAO;
    private final HistoricalDataDAO historicalDataDAO; // [추가] 과거 데이터 조회용 DAO
    private final String   userId;
    private long sessionId;

    private List<MyAssetStatusDTO> myAssetList = new ArrayList<>();
    private BigDecimal             krwBalance  = BigDecimal.ZERO;

    public Asset_MainPanel(String userId, long sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.assetDAO = new AssetDAO();
        this.historicalDataDAO = new HistoricalDataDAO(); // [추가] 초기화

        setLayout(new BorderLayout(0, 0));
        setBackground(Color.WHITE);

        summaryPanel = new Asset_SummaryPanel();
        tablePanel   = new Assets_TablePanel();

        add(summaryPanel, BorderLayout.NORTH);
        add(tablePanel,   BorderLayout.CENTER);

        // 이벤트 리스너 등록
        UpbitWebSocketDao.getInstance().addListener(this);
        BacktestTimeController.getInstance().addTickListener(this); // [추가] 백테스트 틱 리스너 등록

        // 초기 DB 데이터 로드
        initAssetData();
    }
    
    // 세션 ID 업데이트 메서드
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

            // 초기 현재가 = 평단가 (웹소켓/백테스트 갱신 전 임시값)
            BigDecimal initPrice = asset.getAvgBuyPrice() != null ? asset.getAvgBuyPrice() : BigDecimal.ZERO;
            dto.setCurrentPrice(initPrice);
            dto.setTotalValue(dto.getBalance().multiply(initPrice));

            myAssetList.add(dto);
        }

        refreshUI();
    }

    // ── 실시간 웹소켓 콜백 ─────────────────────────────────────────
    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr, String tradeVolumeStr) {
        // 백테스트 중이 아닐 때만 웹소켓 이벤트 처리
        if (!BacktestTimeController.getInstance().isRunning()) {
            boolean related = myAssetList.stream().anyMatch(d -> d.getCurrency().equals(symbol));
            if (related) {
                SwingUtilities.invokeLater(this::refreshUI);
            }
        }
    }

    // ── [추가] 백테스트 틱 콜백 ────────────────────────────────────
    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        // 백테스트 엔진에서 1틱(1초) 진행될 때마다 UI 갱신 요청
        SwingUtilities.invokeLater(this::refreshUI);
    }

    // ── UI 전체 갱신 ──────────────────────────────────────────────
    private void refreshUI() {
        BigDecimal totalEval = BigDecimal.ZERO;
        BigDecimal totalBuy  = BigDecimal.ZERO;

        // [추가] 현재 백테스트 모드인지 확인 및 과거 가격 조회
        boolean isBacktesting = BacktestTimeController.getInstance().isRunning();
        Map<String, TickerDto> backtestPrices = null;
        
        if (isBacktesting) {
            LocalDateTime simTime = BacktestTimeController.getInstance().getCurrentSimTime();
            if (simTime != null) {
                backtestPrices = historicalDataDAO.getTickersAtTime(simTime);
            }
        }

        for (MyAssetStatusDTO dto : myAssetList) {
            BigDecimal cur = null;

            // [변경] 분기 처리: 백테스트 중이면 DB 과거 데이터, 아니면 웹소켓 실시간 데이터 사용
            if (isBacktesting && backtestPrices != null) {
                TickerDto ticker = backtestPrices.get(dto.getCurrency());
                if (ticker != null) {
                    cur = BigDecimal.valueOf(ticker.getTrade_price());
                }
            } else {
                String market = "KRW-" + dto.getCurrency();
                cur = UpbitWebSocketDao.getCurrentPrice(market);
            }

            // 현재가 갱신 및 계산
            if (cur != null && cur.compareTo(BigDecimal.ZERO) > 0) {
                dto.setCurrentPrice(cur);
            }

            BigDecimal eval = dto.getBalance().multiply(dto.getCurrentPrice());
            BigDecimal buy  = dto.getBalance().multiply(dto.getAvgPrice());
            dto.setTotalValue(eval);

            // 수익률 계산
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
}