package com.team.coin_simulator.backtest;

import com.team.coin_simulator.chart.CandleChartPanel;

import javax.swing.*;
import java.time.LocalDateTime;

/**
 * BacktestTimeController ↔ CandleChartPanel 연결 어댑터
 *
 * ■ 역할
 *   - BacktestTickListener 를 구현하여 매 tick 마다 차트 패널에
 *     현재 시뮬레이션 시각을 전달합니다.
 *   - 차트가 매 tick 마다 DB를 조회하는 것은 매우 비싸므로,
 *     "업데이트 주기(throttle)" 를 두어 N분마다 한 번씩만 차트를 갱신합니다.
 *
 * ■ 갱신 주기 결정 로직
 *   배속    | 시뮬 분/tick | throttle(N tick) | 실효 갱신 주기 (실시간 초)
 *   1배속   |  1분/tick   |  4 tick          |  4초 (차트 4분마다)
 *   10배속  | 10분/tick   |  2 tick          |  2초 (차트 20분마다)
 *   30배속  | 30분/tick   |  1 tick          |  1초 (차트 30분마다)
 *   60배속  | 60분/tick   |  1 tick          |  1초 (차트 60분마다)
 *
 * ■ 사용법
 *   CandleChartBacktestAdapter adapter =
 *       new CandleChartBacktestAdapter(chartPanel, historyPanel);
 *   BacktestTimeController.getInstance().addTickListener(adapter);
 */
public class CandleChartBacktestAdapter
        implements BacktestTimeController.BacktestTickListener {

    private final CandleChartPanel chartPanel;

    /** 마지막으로 차트를 갱신한 시뮬레이션 시각 */
    private LocalDateTime lastChartUpdateTime = null;

    /**
     * 차트를 갱신하는 최소 시뮬레이션 간격 (분)
     * 배속에 따라 동적으로 계산됩니다.
     */
    private static final int MIN_CHART_INTERVAL_MINUTES = 4;

    // HistoryPanel 참조 (시세 목록 업데이트)
    private final com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel;
    private final DAO.HistoricalDataDAO historicalDataDAO = new DAO.HistoricalDataDAO();

    public CandleChartBacktestAdapter(
            CandleChartPanel chartPanel,
            com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel) {
        this.chartPanel   = chartPanel;
        this.historyPanel = historyPanel;
    }

    // ════════════════════════════════════════════════
    //  BacktestTickListener 구현
    // ════════════════════════════════════════════════

    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        // ── 1. 차트 갱신 throttle ─────────────────────
        int chartIntervalMinutes = calcChartInterval(speed);

        boolean shouldUpdateChart = (lastChartUpdateTime == null)
            || java.time.temporal.ChronoUnit.MINUTES.between(lastChartUpdateTime, currentSimTime)
               >= chartIntervalMinutes;

        if (shouldUpdateChart) {
            lastChartUpdateTime = currentSimTime;
            final LocalDateTime snapTime = currentSimTime;

            // CandleChartPanel 갱신 (EDT에서 실행)
            SwingUtilities.invokeLater(() ->
                chartPanel.loadHistoricalData(snapTime)
            );
        }

        // ── 2. 마켓 패널(시세 목록) 갱신 ─────────────
        // 가격 변동이 빠른 배속에서 부하를 줄이기 위해 30초 시뮬레이션 간격으로 제한
        boolean shouldUpdateMarket = (lastChartUpdateTime == null)
            || java.time.temporal.ChronoUnit.MINUTES.between(lastChartUpdateTime, currentSimTime)
               >= Math.max(1, speed.getMinutesPerTick());

        if (shouldUpdateMarket) {
            updateMarketPanel(currentSimTime);
        }
    }

    // ════════════════════════════════════════════════
    //  마켓 패널 업데이트 (과거 DB 가격 조회)
    // ════════════════════════════════════════════════

    /**
     * DB의 market_candle 에서 해당 시각의 가격을 조회하고
     * HistoryPanel 의 각 코인 행을 갱신합니다.
     */
    private void updateMarketPanel(LocalDateTime simTime) {
        Thread.ofVirtual().start(() -> {
            try {
                // 현재 시각의 모든 코인 시세 조회
                java.util.Map<String, DTO.TickerDto> tickers =
                    historicalDataDAO.getTickersAtTime(simTime);

                if (tickers.isEmpty()) return;

                SwingUtilities.invokeLater(() -> {
                    for (java.util.Map.Entry<String, DTO.TickerDto> entry : tickers.entrySet()) {
                        String symbol = entry.getKey();
                        DTO.TickerDto ticker = entry.getValue();

                        double price    = ticker.getTrade_price();
                        double flucRate = ticker.getSigned_change_rate() * 100;

                        // 가격 포맷
                        String priceStr;
                        if (price < 1)       priceStr = String.format("%,.5f", price);
                        else if (price < 100) priceStr = String.format("%,.2f", price);
                        else                  priceStr = String.format("%,.0f", price);

                        String flucStr   = String.format("%.2f", flucRate);
                        String accPrStr  = String.format("%,.0f", ticker.getAcc_trade_price());

                        historyPanel.updateCoinPrice(symbol, priceStr, flucStr, accPrStr);
                    }
                });

            } catch (Exception e) {
                System.err.println("[CandleChartBacktestAdapter] 마켓 패널 업데이트 오류: "
                    + e.getMessage());
            }
        });
    }

    // ════════════════════════════════════════════════
    //  배속별 차트 갱신 주기 계산
    // ════════════════════════════════════════════════

    /**
     * 배속이 빠를수록 한 tick 당 더 많은 시간이 흐르므로
     * 차트는 더 자주 갱신해도 괜찮습니다(데이터 변화가 크기 때문).
     * 그러나 매 tick 마다 DB 조회는 부담이므로 최소 1 tick 간격을 보장합니다.
     */
    private int calcChartInterval(BacktestSpeed speed) {
        return switch (speed) {
            case SPEED_1X  -> 4;   // 4분 간격
            case SPEED_10X -> 10;  // 10분 간격 (1 tick)
            case SPEED_30X -> 30;  // 30분 간격 (1 tick)
            case SPEED_60X -> 60;  // 60분 간격 (1 tick)
        };
    }
}