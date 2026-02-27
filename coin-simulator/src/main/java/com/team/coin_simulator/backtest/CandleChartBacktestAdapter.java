package com.team.coin_simulator.backtest;

import com.team.coin_simulator.chart.CandleChartPanel;
import com.team.coin_simulator.Market_Order.OrderPanel;
import DAO.HistoricalDataDAO;
import DTO.TickerDto;

import javax.swing.*;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 백테스트 시간 흐름에 따라 차트와 마켓 패널을 갱신하는 어댑터
 * 성능 최적화를 위해 하루치 데이터를 메모리에 캐싱하고 다음 날 데이터를 미리 로드(Pre-fetching)함
 */
public class CandleChartBacktestAdapter implements BacktestTimeController.BacktestTickListener {

    private final CandleChartPanel chartPanel;
    private final com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel;
    private final OrderPanel orderPanel;
    private final HistoricalDataDAO historicalDataDAO = new HistoricalDataDAO();

    private LocalDateTime lastChartUpdateTime  = null;
    private LocalDateTime lastMarketUpdateTime = null;

    // 인메모리 데이터 저장소
    private final Map<String, Double> dailyAccVolume = new ConcurrentHashMap<>();
    private final Map<String, Double> dailyOpenPrice = new ConcurrentHashMap<>();
    
    // 하루치 1분봉 데이터 캐시 (시간별 매핑)
    private Map<LocalDateTime, Map<String, TickerDto>> currentDayCache = new ConcurrentHashMap<>();
    private Map<LocalDateTime, Map<String, TickerDto>> nextDayCache = new ConcurrentHashMap<>();
    
    private LocalDateTime currentAccDay = null; // 오전 9시 기준 현재 날짜

    public CandleChartBacktestAdapter(CandleChartPanel chartPanel, 
            com.team.coin_simulator.Market_Panel.HistoryPanel historyPanel,
            OrderPanel orderPanel) { 
        this.chartPanel   = chartPanel;
        this.historyPanel = historyPanel;
        this.orderPanel   = orderPanel;
    }

    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        
    	// ── 0. 날짜 변경(오전 9시) 시 캐시 교체 및 프리페칭 수행 ──
        LocalDateTime startOfDay = getStartOfDay(currentSimTime);
        if (currentAccDay == null || !currentAccDay.equals(startOfDay)) {
            currentAccDay = startOfDay;
            
            // 1. 캐시 교체 로직
            if (!nextDayCache.isEmpty()) {
                currentDayCache = nextDayCache;
                nextDayCache = new ConcurrentHashMap<>(); 
                System.out.println("[Cache] 내일 데이터를 현재 캐시로 전환 완료: " + currentAccDay);
            } else {
                // DB 조회는 하루치 캔들 캐싱 딱 1번만 실행
                currentDayCache = historicalDataDAO.getDailyCandlesForCache(startOfDay);
            }

            // 2. 무거운 DB 쿼리(시가, 거래대금) 대신 자바 메모리에서 0.01초 만에 직접 계산
            extractInitDataFromCache(currentSimTime);

            // 3. 비동기로 다음 날짜 데이터 미리 가져오기 (Prefetch)
            CompletableFuture.runAsync(() -> {
                LocalDateTime nextDay = startOfDay.plusDays(1);
                nextDayCache = historicalDataDAO.getDailyCandlesForCache(nextDay);
                System.out.println("[Cache] 다음 날짜(" + nextDay + ") 데이터 백그라운드 로드 완료");
            });
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
            LocalDateTime startTarget = (lastMarketUpdateTime != null) ? lastMarketUpdateTime : currentSimTime.minusMinutes(1);
            lastMarketUpdateTime = currentSimTime;
            
            // 구간(이전 갱신시간 ~ 현재 시뮬레이션 시간)을 넘겨 스레드 내부에서 순차 처리하게 함
            updateMarketPanel(startTarget, currentSimTime);
        }
    }
    
    
    /**
     * DB 조회 없이 캐시된 하루치 1분봉 데이터를 순회하며 
     * 시뮬레이션 현재 시간까지의 '누적 거래대금'과 당일 '시가'를 직접 계산합니다.
     */
    private void extractInitDataFromCache(LocalDateTime targetTime) {
        dailyAccVolume.clear();
        dailyOpenPrice.clear();
        
        // 코인별로 가장 첫 번째 캔들의 시간을 기록하여 시가를 찾기 위한 맵
        Map<String, LocalDateTime> firstCandleTimeMap = new ConcurrentHashMap<>();

        for (Map.Entry<LocalDateTime, Map<String, DTO.TickerDto>> entry : currentDayCache.entrySet()) {
            LocalDateTime candleTime = entry.getKey();
            
            // 핵심 1: 현재 시뮬레이션 시간(targetTime)을 초과하는 미래 데이터는 절대 누적하지 않음
            if (candleTime.isAfter(targetTime)) {
                continue;
            }

            for (Map.Entry<String, DTO.TickerDto> coinEntry : entry.getValue().entrySet()) {
                String symbol = coinEntry.getKey();
                DTO.TickerDto ticker = coinEntry.getValue();

                // 1. 누적 거래대금 합산
                double currentAcc = dailyAccVolume.getOrDefault(symbol, 0.0);
                dailyAccVolume.put(symbol, currentAcc + ticker.getAcc_trade_price());

                // 2. 당일 시가(Open Price) 찾기 및 복원
                if (!firstCandleTimeMap.containsKey(symbol) || candleTime.isBefore(firstCandleTimeMap.get(symbol))) {
                    firstCandleTimeMap.put(symbol, candleTime);
                    
                    // 핵심 2: TickerDto에 없는 시가를 '현재가'와 '등락률'을 이용해 수학적으로 역산
                    double tradePrice = ticker.getTrade_price();
                    double changeRate = ticker.getSigned_change_rate(); // ex) 0.05 (5%)
                    
                    double openPrice = tradePrice;
                    if (changeRate != 0.0 && changeRate != -1.0) {
                        openPrice = tradePrice / (1.0 + changeRate);
                    }
                    
                    dailyOpenPrice.put(symbol, openPrice);
                }
            }
        }
    }
    
    
    
    private void updateMarketPanel(LocalDateTime startTime, LocalDateTime endTime) {
        Thread.ofVirtual().start(() -> {
            try {
                LocalDateTime cursor = startTime.plusMinutes(1);
                Map<String, DTO.TickerDto> lastTickers = null;

                // 1. 단일 비동기 스레드 내에서 건너뛴 시간만큼 순차적으로 DB 조회 및 누적 (스레드 충돌 방지)
                while (!cursor.isAfter(endTime)) {
                    Map<String, DTO.TickerDto> tickers = historicalDataDAO.getTickersAtTime(cursor);
                    if (!tickers.isEmpty()) {
                        lastTickers = tickers;
                        for (Map.Entry<String, DTO.TickerDto> entry : tickers.entrySet()) {
                            String symbol = entry.getKey().replace("KRW-", "");
                            double minuteVolume = entry.getValue().getAcc_trade_price();
                            
                            // 맵 데이터 누적
                            double accumulated = dailyAccVolume.getOrDefault(symbol, 0.0) + minuteVolume;
                            dailyAccVolume.put(symbol, accumulated);
                        }
                    }
                    cursor = cursor.plusMinutes(1);
                }

                // 2. 누적 완료 후 마지막 데이터가 없다면 UI 업데이트 생략
                if (lastTickers == null) return;
                
                final Map<String, DTO.TickerDto> finalTickers = lastTickers;

                // 3. UI 갱신은 점프한 시간의 "최종 상태" 기준으로 1회만 실행 (UI 렉 방지)
                SwingUtilities.invokeLater(() -> {
                    for (Map.Entry<String, DTO.TickerDto> entry : finalTickers.entrySet()) {
                        String symbol = entry.getKey().replace("KRW-", "");
                        DTO.TickerDto ticker = entry.getValue();

                        double price = ticker.getTrade_price();
                        double openPrice = dailyOpenPrice.getOrDefault(symbol, price);
                        
                        double flucRate = 0.0;
                        if (openPrice > 0) {
                            flucRate = ((price - openPrice) / openPrice) * 100;
                        }

                        String priceStr;
                        if (price < 1)       priceStr = String.format("%,.5f", price);
                        else if (price < 100) priceStr = String.format("%,.2f", price);
                        else                  priceStr = String.format("%,.0f", price);

                        String flucStr = String.format("%.2f", flucRate);
                        
                        double finalAccumulated = dailyAccVolume.getOrDefault(symbol, 0.0);
                        String accPrStr;
                        if (finalAccumulated >= 100_000_000) {
                            accPrStr = String.format("%,.0f백만", finalAccumulated / 1_000_000);
                        } else {
                            accPrStr = String.format("%,.0f", finalAccumulated);
                        }

                        historyPanel.updateCoinPrice(symbol, priceStr, flucStr, accPrStr);
                        if (orderPanel != null) {
                            orderPanel.onTickerUpdate(symbol, priceStr, flucStr, accPrStr, "15.0");
                        }
                    }
                });
            } catch (Exception e) {
                System.err.println("[CandleChartBacktestAdapter] 마켓 패널 업데이트 오류: " + e.getMessage());
            }
        });
    }

    /**
     * DB 조회 없이 메모리 캐시에서 즉시 데이터를 추출하여 UI 갱신
     */
    private void updateMarketPanelFromCache(LocalDateTime simTime) {
        // 초/나노초 절사하여 캐시 키 매칭
        LocalDateTime keyTime = simTime.withSecond(0).withNano(0);
        Map<String, TickerDto> tickers = currentDayCache.get(keyTime);
        
        if (tickers == null || tickers.isEmpty()) return;

        SwingUtilities.invokeLater(() -> {
            for (Map.Entry<String, TickerDto> entry : tickers.entrySet()) {
                String symbol = entry.getKey();
                TickerDto ticker = entry.getValue();

                // 누적 거래대금 계산
                double minuteVolume = ticker.getAcc_trade_price();
                double accumulated = dailyAccVolume.getOrDefault(symbol, 0.0) + minuteVolume;
                dailyAccVolume.put(symbol, accumulated);

                // 등락률 계산 (당일 시가 기준)
                double price = ticker.getTrade_price();
                double openPrice = dailyOpenPrice.getOrDefault(symbol, price);
                double flucRate = (openPrice > 0) ? ((price - openPrice) / openPrice) * 100 : 0.0;

                // 문자열 포맷팅 및 UI 업데이트
                String priceStr = formatPrice(price);
                String flucStr = String.format("%.2f", flucRate);
                String accPrStr = formatAccumulatedVolume(accumulated);

                historyPanel.updateCoinPrice(symbol, priceStr, flucStr, accPrStr);
                if (orderPanel != null) {
                    orderPanel.onTickerUpdate(symbol, priceStr, flucStr, accPrStr, "15.0");
                }
            }
        });
    }

    private String formatPrice(double price) {
        if (price < 1) return String.format("%,.5f", price);
        if (price < 100) return String.format("%,.2f", price);
        return String.format("%,.0f", price);
    }

    private String formatAccumulatedVolume(double accumulated) {
        if (accumulated >= 100_000_000) {
            return String.format("%,.0f백만", accumulated / 1_000_000);
        }
        return String.format("%,.0f", accumulated);
    }

    private int calcChartInterval(BacktestSpeed speed) {
        return switch (speed) {
            case SPEED_1X  -> 1;
            case SPEED_10X -> 10;
            case SPEED_30X -> 30;
            case SPEED_60X -> 60;
        };
    }

    private LocalDateTime getStartOfDay(LocalDateTime time) {
        LocalDateTime start = time.withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (time.isBefore(start)) {
            start = start.minusDays(1);
        }
        return start;
    }
}