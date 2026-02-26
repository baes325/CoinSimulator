package com.team.coin_simulator.Alerts;

import DAO.*;
import DTO.PriceAlertDTO;

import com.team.coin_simulator.*;
import com.team.coin_simulator.Alerts.NotificationUtil;
import javax.swing.JFrame;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.swing.SwingUtilities;

public class PriceAlertService implements UpbitWebSocketDao.TickerListener {
    
    private JFrame mainFrame;
    private String currentUser;
    private PriceAlertDAO alertDAO;

    //[지정가 알림용] 메모리 캐시 (DB 과부하 방지)
    private List<PriceAlertDTO> activeAlerts;
    
    //[급등락 알림용]
    private Map<String, LinkedList<BigDecimal>> priceHistory = new ConcurrentHashMap<>();

    // 생성자에서 userId를 받도록 수정
    public PriceAlertService(JFrame mainFrame, String userId) {
        this.mainFrame = mainFrame;
        this.currentUser = userId;
        this.alertDAO = new PriceAlertDAO();
        
        // 프로그램 시작 시 DB에서 알림 목록 장전!
        reloadAlertsFromDB();
        
        UpbitWebSocketDao.getInstance().addListener(this);
    }
    
    // 새 알림이 추가되었을 때 캐시를 갱신하는 메서드
    public void reloadAlertsFromDB() {
        this.activeAlerts = new CopyOnWriteArrayList<>(alertDAO.getActiveAlerts(currentUser));
        System.out.println(">> [알림 시스템] 활성화된 알림 " + activeAlerts.size() + "개 로드 완료.");
    }

    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr, String tradeVolumeStr) {
        //현재가 파싱
        String cleanPrice = priceStr.replace(",", "").replace(" KRW", "").trim();
        if (cleanPrice.isEmpty() || cleanPrice.equals("연결중...")) return;
        BigDecimal currentPrice;
        try {
            currentPrice = new BigDecimal(cleanPrice);
        } catch (Exception e) {
            return; // 숫자가 아닌 이상한 문자열이 들어오면 무시
        }

        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return; // 0원이면 알림 검사를 아예 안 하고 돌려보냅니다.
        }
        
        //메모리 캐시 기반의 지정가 알림 검사 (DB 부하 Zero!)
        checkPriceAlertsInMemory(symbol, currentPrice);
        
        //기존 급등락 로직
        checkVolatility(symbol, currentPrice);
    }

    //DB SELECT 쿼리 대신 메모리(activeAlerts)에서 초고속 검사
    private void checkPriceAlertsInMemory(String market, BigDecimal currentPrice) {
        if (activeAlerts == null || activeAlerts.isEmpty()) return;
        String wsCoin = market.replace("KRW-", "");

        for (PriceAlertDTO alert : activeAlerts) {
            //DB 코인명에서도 KRW- 제거 (DB에 "KRW-BTC"로 저장되어 있어도 "BTC"로 변환됨)
            String dbCoin = alert.getMarket().replace("KRW-", "");
            if (!dbCoin.equals(wsCoin)) continue; 

            boolean isTriggered = false;
            BigDecimal target = alert.getTargetPrice();

            //조건 검사
            if (alert.getConditionType().equals("ABOVE") && currentPrice.compareTo(target) >= 0) {
                isTriggered = true;
            } else if (alert.getConditionType().equals("BELOW") && currentPrice.compareTo(target) <= 0) {
                isTriggered = true;
            }

            if (isTriggered) {
                // 1. 리스트에서 즉시 제거 (중복 알림 폭탄 방지)
                activeAlerts.remove(alert);
                
                // 2. 백그라운드 스레드에서 DB 업데이트 및 알림 발송 (UI 멈춤 방지)
                new Thread(() -> {
                    alertDAO.markAsTriggered(alert.getAlertId()); // DB 상태 변경 (is_active = FALSE)
                    
                    String conditionKr = alert.getConditionType().equals("ABOVE") ? "상승 돌파" : "하락 돌파";
                    String msg = String.format("[가격 알림] %s 코인이 %,.0f원에 도달했습니다! (%s)", 
                                               market, target, conditionKr);
                    
                    SwingUtilities.invokeLater(() -> NotificationUtil.showToast(mainFrame, msg));
                }).start();
            }
        }
    }
    
    // 코인별 가격 기록 (최근 180초 데이터)
    public void checkVolatility(String symbol, BigDecimal currentPrice) {
        LinkedList<BigDecimal> history = priceHistory.computeIfAbsent(symbol, k -> new LinkedList<>());
        
        // 1. 현재 가격 추가
        history.add(currentPrice);
        
        // 2. 데이터 개수 체크
        if (history.size() > 180) {
            BigDecimal oldPrice = history.poll(); // n초 전 가격 꺼내기
            
            // [안전장치] 0으로 나누기 오류 방지
            if (oldPrice.compareTo(BigDecimal.ZERO) == 0) return;

            // 3. 급등락 계산
            BigDecimal diff = currentPrice.subtract(oldPrice);
            BigDecimal rate = diff.multiply(new BigDecimal("100"))
                    .divide(oldPrice, 6, RoundingMode.HALF_UP);
            
            // 4. 절대값이 3% 이상이면 알림 발송
            if (rate.abs().compareTo(new BigDecimal("3.0")) >= 0) {
                
                String type = rate.compareTo(BigDecimal.ZERO) > 0 ? "급등" : "급락";
                String msg = String.format("⚠️ [%s] %s 발생! (%.2f%% 변동)", symbol, type, rate);
                javax.swing.SwingUtilities.invokeLater(() -> 
                    com.team.coin_simulator.Alerts.NotificationUtil.showToast(mainFrame, msg)
                );
                
                history.clear(); // 알림 폭탄 방지 (기록 초기화)
            }
        }
    }
}