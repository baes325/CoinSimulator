package com.team.coin_simulator.Market_Panel;

import java.awt.Color;
import java.math.BigDecimal;
import java.math.RoundingMode;

import javax.swing.JLabel;

import DTO.AssetDTO;

// CoinRowPanel을 상속받아 웹소켓 업데이트 로직을 재활용
public class AssetRowPanel extends CoinRowPanel {

    private final BigDecimal balance;   // 총 보유 수량
    private final BigDecimal avgPrice;  // 매수 평균가
    private JLabel lblAvgPrice;         // 매수평균가 표시 라벨 (기존 lblValuation에서 변경)
    private JLabel lblPnl;              // 수익률 표시 라벨

    public AssetRowPanel(String name, AssetDTO asset) {
        // 부모 생성자 호출 (초기값 세팅)
        super(name, "0", "0.00%", "0");

        this.balance = asset.getTotalAmount();
        this.avgPrice = asset.getAvgBuyPrice();
        
        // 두 번째 컴포넌트: 매수평균가
        this.lblAvgPrice = (JLabel) getComponent(1); 
        // 세 번째 컴포넌트: 수익률
        this.lblPnl = (JLabel) getComponent(2);
        // 네 번째 컴포넌트: 보유수량 표시로 변경
        ((JLabel) getComponent(3)).setText(String.format("%.4f개", balance));

        // 매수평균가는 변하지 않으므로 생성자에서 1회만 세팅
        this.lblAvgPrice.setText(String.format("%,.0f", avgPrice));
    }

    @Override
    public void updateData(String currentPriceStr, String flucStr, String accTradePrice) {
        try {
            // 1. 현재가 파싱 (콤마 제거)
            BigDecimal currentPrice = new BigDecimal(currentPriceStr.replaceAll(",", ""));

            // 2. 수익률 계산: ((현재가 - 평단가) / 평단가) * 100
            // (평가금액 계산 로직은 삭제됨)
            if (avgPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal diff = currentPrice.subtract(avgPrice);
                BigDecimal pnlRate = diff.divide(avgPrice, 4, RoundingMode.HALF_UP)
                                         .multiply(new BigDecimal(100));

                double pnl = pnlRate.doubleValue();
                String pnlStr = String.format("%.2f%%", pnl);
                
                // 3. 색상 처리
                if (pnl > 0) {
                    lblPnl.setText("+" + pnlStr);
                    lblPnl.setForeground(Color.RED);
                } else if (pnl < 0) {
                    lblPnl.setText(pnlStr);
                    lblPnl.setForeground(Color.BLUE);
                } else {
                    lblPnl.setText("0.00%");
                    lblPnl.setForeground(Color.BLACK);
                }
            }
        } catch (Exception e) {
            // 파싱 에러 방지
        }
    }
}