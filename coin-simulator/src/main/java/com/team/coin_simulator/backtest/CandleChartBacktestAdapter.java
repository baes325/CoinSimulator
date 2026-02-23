package com.team.coin_simulator.backtest;

import com.team.coin_simulator.chart.CandleChartPanel;
import javax.swing.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CandleChartBacktestAdapter implements BacktestTimeController.BacktestTickListener {

    private final CandleChartPanel chartPanel;
    private final com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel;
    private final DAO.HistoricalDataDAO historicalDataDAO = new DAO.HistoricalDataDAO();

    private LocalDateTime lastChartUpdateTime  = null;
    private LocalDateTime lastMarketUpdateTime = null;

    // [1안 구현] 인메모리 누적 거래대금 저장소 및 날짜 관리
    private final Map<String, Double> dailyAccVolume = new ConcurrentHashMap<>();
    private LocalDateTime currentAccDay = null; // 오전 9시 기준 현재 날짜

    public CandleChartBacktestAdapter(CandleChartPanel chartPanel, com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel) {
        this.chartPanel   = chartPanel;
        this.historyPanel = historyPanel;
    }

    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        
        // ── 0. 날짜 변경(오전 9시) 또는 세션 최초 시작 시 거래대금 DB 동기화 ──
        LocalDateTime startOfDay = getStartOfDay(currentSimTime);
        if (currentAccDay == null || !currentAccDay.equals(startOfDay)) {
            currentAccDay = startOfDay;
            // 딱 1번만 DB를 조회하여 Map을 초기화합니다.
            Map<String, Double> initData = historicalDataDAO.getInitDailyAccTradePrice(currentSimTime);
            dailyAccVolume.clear();
            dailyAccVolume.putAll(initData);
            System.out.println("[CandleChartBacktestAdapter] 거래대금 누적값 초기화 (기준일: " + currentAccDay + ")");
        }

        // ── 1. 차트 갱신 ─────────────────────────────
        int chartIntervalMinutes = calcChartInterval(speed);
        boolean shouldUpdateChart = (lastChartUpdateTime == null)
            || java.time.temporal.ChronoUnit.MINUTES.between(lastChartUpdateTime, currentSimTime) >= chartIntervalMinutes;

        if (shouldUpdateChart) {
            lastChartUpdateTime = currentSimTime;
            final LocalDateTime snapTime = currentSimTime;
            SwingUtilities.invokeLater(() -> chartPanel.loadHistoricalData(snapTime));
        }

        // ── 2. 마켓 패널 갱신 ─────────────────────────
        int marketInterval = Math.max(1, speed.getMinutesPerTick());
        boolean shouldUpdateMarket = (lastMarketUpdateTime == null)
            || java.time.temporal.ChronoUnit.MINUTES.between(lastMarketUpdateTime, currentSimTime) >= marketInterval;

        if (shouldUpdateMarket) {
            lastMarketUpdateTime = currentSimTime;
            updateMarketPanel(currentSimTime);
        }
    }

    private void updateMarketPanel(LocalDateTime simTime) {
        Thread.ofVirtual().start(() -> {
            try {
                Map<String, DTO.TickerDto> tickers = historicalDataDAO.getTickersAtTime(simTime);
                if (tickers.isEmpty()) return;

                SwingUtilities.invokeLater(() -> {
                    for (Map.Entry<String, DTO.TickerDto> entry : tickers.entrySet()) {
                        String symbol = entry.getKey();
                        if (symbol.startsWith("KRW-")) {
                            symbol = symbol.replace("KRW-", "");
                        }

                        DTO.TickerDto ticker = entry.getValue();

                        // [수정] 1분 거래대금을 가져와 인메모리에 계속 누적시킵니다.
                        double minuteVolume = ticker.getAcc_trade_price();
                        double accumulated = dailyAccVolume.getOrDefault(symbol, 0.0) + minuteVolume;
                        dailyAccVolume.put(symbol, accumulated); // 갱신된 누적값 저장

                        double price    = ticker.getTrade_price();
                        double flucRate = ticker.getSigned_change_rate() * 100;

                        String priceStr;
                        if (price < 1)       priceStr = String.format("%,.5f", price);
                        else if (price < 100) priceStr = String.format("%,.2f", price);
                        else                  priceStr = String.format("%,.0f", price);

                        String flucStr   = String.format("%.2f", flucRate);
                        // [수정] 화면에는 1분값이 아닌 "누적값(accumulated)"을 띄워줍니다.
                        String accPrStr  = String.format("%,.0f", accumulated);

                        historyPanel.updateCoinPrice(symbol, priceStr, flucStr, accPrStr);
                    }
                });
            } catch (Exception e) {
                System.err.println("[CandleChartBacktestAdapter] 마켓 패널 업데이트 오류: " + e.getMessage());
            }
        });
    }

    private int calcChartInterval(BacktestSpeed speed) {
        return switch (speed) {
            case SPEED_1X  -> 1;
            case SPEED_10X -> 10;
            case SPEED_30X -> 30;
            case SPEED_60X -> 60;
        };
    }

    // 업비트 일봉 갱신 기준인 오전 9시 계산 유틸리티
    private LocalDateTime getStartOfDay(LocalDateTime time) {
        LocalDateTime start = time.withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (time.isBefore(start)) {
            start = start.minusDays(1);
        }
        return start;
    }
}