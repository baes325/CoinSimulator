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
    private final Map<String, Double> dailyOpenPrice = new ConcurrentHashMap<>(); // 추가!
    
    private LocalDateTime currentAccDay = null; // 오전 9시 기준 현재 날짜

    public CandleChartBacktestAdapter(CandleChartPanel chartPanel, com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel) {
        this.chartPanel   = chartPanel;
        this.historyPanel = historyPanel;
    }

    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        
        // ── 0. 날짜 변경(오전 9시) 또는 세션 최초 시작 시 거래대금 DB 동기화 ──
    	// ── 0. 날짜 변경(오전 9시) 또는 세션 최초 시작 시 DB 동기화 ──
        LocalDateTime startOfDay = getStartOfDay(currentSimTime);
        if (currentAccDay == null || !currentAccDay.equals(startOfDay)) {
            currentAccDay = startOfDay;
            
            // 1. 거래대금 동기화
            Map<String, Double> initData = historicalDataDAO.getInitDailyAccTradePrice(currentSimTime);
            dailyAccVolume.clear();
            dailyAccVolume.putAll(initData);
            
            // 2. 당일 오전 9시 시가 동기화 (추가!)
            Map<String, Double> openData = historicalDataDAO.getDailyOpenPrices(currentSimTime);
            dailyOpenPrice.clear();
            dailyOpenPrice.putAll(openData);
            
            System.out.println("[CandleChartBacktestAdapter] 거래대금 및 시가 초기화 (기준일: " + currentAccDay + ")");
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

                        double price = ticker.getTrade_price();

	                     // [수정] 1분봉의 등락률이 아닌, 메모리에 저장된 "오늘 아침 9시 시가" 기준으로 등락률 재계산
	                     double openPrice = dailyOpenPrice.getOrDefault(symbol, price); // 데이터가 없으면 현재가로 대체(0% 방어)
	                     double flucRate = 0.0;
	                     if (openPrice > 0) {
	                         flucRate = ((price - openPrice) / openPrice) * 100;
	                     }

                        String priceStr;
                        if (price < 1)       priceStr = String.format("%,.5f", price);
                        else if (price < 100) priceStr = String.format("%,.2f", price);
                        else                  priceStr = String.format("%,.0f", price);

                        String flucStr   = String.format("%.2f", flucRate);
                        // [수정] 거래대금이 1억 이상일 경우 100만 단위로 나누고 "백만"을 붙입니다.
                        String accPrStr;
                        if (accumulated >= 100_000_000) {
                            accPrStr = String.format("%,.0f백만", accumulated / 1_000_000);
                        } else {
                            accPrStr = String.format("%,.0f", accumulated);
                        }

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