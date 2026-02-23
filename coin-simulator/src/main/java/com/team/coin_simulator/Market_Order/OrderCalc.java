package com.team.coin_simulator.Market_Order;

import java.math.BigDecimal;
import java.math.RoundingMode;

public class OrderCalc {
    // 수수료율 0.05% (업비트 등 국내 거래소 기준)
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005"); 
    private static final int SCALE_BTC = 8; // 코인 수량 소수점
    private static final int SCALE_KRW = 0; // 원화 금액 소수점 (정수 처리)

    //[매수] 현재 잔고로 살 수 있는 최대 수량 계산
    public static String getAvailableVolumeString(String priceText, BigDecimal krwBalance) {
        try {
            if (priceText == null || priceText.replace(",", "").trim().isEmpty()) return "0.00000000";
            
            BigDecimal price = new BigDecimal(priceText.replace(",", "").trim());
            if (price.compareTo(BigDecimal.ZERO) <= 0) return "0.00000000";

            // 1단위 구매 시 드는 총 비용 = 가격 + 수수료(가격 * 0.0005)
            BigDecimal costPerUnit = price.multiply(BigDecimal.ONE.add(FEE_RATE));
            
            // 최대 수량 = 잔고 / 단위당 비용
            BigDecimal maxBTC = krwBalance.divide(costPerUnit, SCALE_BTC, RoundingMode.DOWN);
            return String.format("%.8f", maxBTC);
        } catch (Exception e) {
            return "0.00000000";
        }
    }

    //[매도] 매도 시 수수료를 제외하고 실제로 받게 될 원화 계산
    public static String getExpectedSellKRWString(String priceText, String qtyText) {
        try {
            if (priceText == null || qtyText == null) return "0";
            
            BigDecimal price = new BigDecimal(priceText.replace(",", "").trim());
            BigDecimal qty = new BigDecimal(qtyText.replace(",", "").trim());
            
            if (price.compareTo(BigDecimal.ZERO) <= 0 || qty.compareTo(BigDecimal.ZERO) <= 0) return "0";

            // 총 매도 대금 = 가격 * 수량
            BigDecimal totalSellAmount = price.multiply(qty);
            // 수수료 = 총 매도 대금 * 0.0005
            BigDecimal fee = totalSellAmount.multiply(FEE_RATE);
            // 실 수령액 = 총 매도 대금 - 수수료
            BigDecimal netProceeds = totalSellAmount.subtract(fee);

            // 원화는 소수점 없이 콤마만 찍어서 반환
            return String.format("%,.0f", netProceeds.setScale(SCALE_KRW, RoundingMode.DOWN));
        } catch (Exception e) {
            return "0";
        }
    }

    //[공통] 단순 총액 계산 (수수료 제외 가격 * 수량)

    public static BigDecimal calcTotalCost(BigDecimal price, BigDecimal qty) {
        if (price == null || qty == null) return BigDecimal.ZERO;
        return price.multiply(qty).setScale(SCALE_KRW, RoundingMode.DOWN);
    }
 // [시장가 매수] 입력한 총액(KRW)으로 살 수 있는 코인 수량 계산 (수수료 반영)
    public static BigDecimal calculateMarketBuyQuantity(BigDecimal totalAmountKRW, BigDecimal currentPrice) {
        if (totalAmountKRW == null || currentPrice == null || currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        
        // 1 코인당 실제 구매 비용 = 현재가 + 수수료(현재가 * 0.0005)
        BigDecimal costPerUnit = currentPrice.multiply(BigDecimal.ONE.add(FEE_RATE));
        
        // 살 수 있는 수량 = 내가 낸 돈 / 1 코인당 구매 비용 (소수점 8자리 내림)
        return totalAmountKRW.divide(costPerUnit, SCALE_BTC, RoundingMode.DOWN);
    }
    
 //[퍼센트 계산]
    // 지정가 매수 시: 내 원화(KRW)의 %로 살 수 있는 '코인 수량' 계산
    public static BigDecimal calcPercentLimitBuyQty(BigDecimal availKrw, double percent, BigDecimal limitPrice) {
        if (limitPrice == null || limitPrice.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
        BigDecimal targetKrw = availKrw.multiply(BigDecimal.valueOf(percent));
        // 수량 = 투자할 돈 / 1코인 가격 (소수점 8자리 버림)
        return targetKrw.divide(limitPrice, 8, BigDecimal.ROUND_DOWN).stripTrailingZeros();
    }

    //매도 시 (지정가/시장가 공통): 내 코인 잔고의 %에 해당하는 '코인 수량' 계산
    public static BigDecimal calcPercentSellQty(BigDecimal availCoin, double percent) {
        return availCoin.multiply(BigDecimal.valueOf(percent)).setScale(8, BigDecimal.ROUND_DOWN).stripTrailingZeros();
    }

    //시장가 매수 시: 내 원화(KRW)의 %에 해당하는 '투자 금액(KRW)' 계산
    public static BigDecimal calcPercentMarketBuyAmount(BigDecimal availKrw, double percent) {
        // 원화는 소수점이 없으므로 0자리에서 버림
        return availKrw.multiply(BigDecimal.valueOf(percent)).setScale(0, BigDecimal.ROUND_DOWN);
    }
    
 // 호가 단위(Tick Size) 계산 로직
    /**
     * 현재 가격에 맞춰 업비트 기준의 호가 단위를 반환
     */
    public static BigDecimal getTickSize(BigDecimal price) {
        double p = price.doubleValue();
        if (p >= 2000000) return new BigDecimal("1000"); // 200만 원 이상은 1,000원 단위
        if (p >= 1000000) return new BigDecimal("500");  // 100만 원 이상은 500원 단위
        if (p >= 500000) return new BigDecimal("100");   // 50만 원 이상은 100원 단위
        if (p >= 100000) return new BigDecimal("50");    // 10만 원 이상은 50원 단위
        if (p >= 10000) return new BigDecimal("10");     // 1만 원 이상은 10원 단위
        if (p >= 1000) return new BigDecimal("1");       // 1천 원 이상은 1원 단위
        if (p >= 100) return new BigDecimal("0.1");      // 100원 이상은 0.1원 단위
        if (p >= 10) return new BigDecimal("0.01");      // 10원 이상은 0.01원 단위
        return new BigDecimal("0.001");                  // 아주 싼 동전 코인들
    }
}