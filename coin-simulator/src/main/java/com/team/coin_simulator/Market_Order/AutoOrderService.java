package com.team.coin_simulator.Market_Order;

import DAO.AutoOrderDAO;
import DAO.OrderDAO;
import DAO.UpbitWebSocketDao;
import DTO.AutoOrderDTO;
import DTO.OrderDTO;
import com.team.coin_simulator.Alerts.NotificationUtil;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AutoOrderService implements UpbitWebSocketDao.TickerListener {

    private AutoOrderDAO autoOrderDAO = new AutoOrderDAO();
    private OrderDAO orderDAO = new OrderDAO();
    
    private List<AutoOrderDTO> activeAutoOrders = new CopyOnWriteArrayList<>();
    private String currentUserId;
    private long currentSessionId;
    private JFrame mainFrame; // 체결 완료 팝업을 띄우기 위함

    public AutoOrderService(JFrame mainFrame, String userId, long sessionId) {
        this.mainFrame = mainFrame;
        this.currentUserId = userId;
        this.currentSessionId = sessionId;
        reloadAutoOrdersFromDB();
    }

    // DB에서 살아있는 자동 매매 목록을 싹 가져와서 장전합니다!
    public void reloadAutoOrdersFromDB() {
        activeAutoOrders.clear();
        activeAutoOrders.addAll(autoOrderDAO.getActiveAutoOrders(currentUserId, currentSessionId));
        System.out.println("[자동 매매 엔진] 준비 완료: " + activeAutoOrders.size() + "개");
    }

    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr, String tradeVolumeStr) {
        if (activeAutoOrders.isEmpty()) return;

        String cleanPrice = priceStr.replace(",", "").replace(" KRW", "").trim();
        if (cleanPrice.isEmpty() || cleanPrice.equals("연결중...")) return;

        BigDecimal currentPrice;
        try { currentPrice = new BigDecimal(cleanPrice); } 
        catch (Exception e) { return; }

        if (currentPrice.compareTo(BigDecimal.ZERO) <= 0) return; // 0원 버그 방어막!

        String wsCoin = symbol.replace("KRW-", "");

        for (AutoOrderDTO order : activeAutoOrders) {
            String dbCoin = order.getMarket().replace("KRW-", "");
            if (!dbCoin.equals(wsCoin)) continue;

            boolean isTriggered = false;
            BigDecimal target = order.getTriggerPrice();

            // 조건 달성 여부 체크 (ABOVE: 이상, BELOW: 이하)
            if (order.getConditionType().equals("ABOVE") && currentPrice.compareTo(target) >= 0) isTriggered = true;
            else if (order.getConditionType().equals("BELOW") && currentPrice.compareTo(target) <= 0) isTriggered = true;

            if (isTriggered) {
                // 1. 메모리에서 빼기 (중복 체결 방지)
                activeAutoOrders.remove(order);
                System.out.println("[자동 매매 격발!] " + dbCoin + " | 조건 도달: " + currentPrice);

                new Thread(() -> {
                    // 2. DB에서 사망 처리
                    autoOrderDAO.markAsTriggered(order.getAutoId());

                    // 3. 시장가 주문 강제 실행!
                    executeMarketOrderNow(order, currentPrice);
                }).start();
            }
        }
    }

    // 💡 방아쇠를 당겨 실제 매매를 진행하는 메서드
    private void executeMarketOrderNow(AutoOrderDTO autoOrder, BigDecimal currentPrice) {
        OrderDTO executionOrder = new OrderDTO();
        executionOrder.setOrderId(System.currentTimeMillis()); // 고유 주문번호 생성
        executionOrder.setUserId(autoOrder.getUserId());
        executionOrder.setSessionId(autoOrder.getSessionId());
        executionOrder.setMarket(autoOrder.getMarket());
        executionOrder.setSide(autoOrder.getSide()); // "BID" or "ASK"

        BigDecimal tradeTotalAmt = currentPrice.multiply(autoOrder.getVolume()); // 총 금액 = 현재가 * 수량

        // 회원님의 기존 시장가 주문 로직 호출!
        boolean success = orderDAO.executeMarketOrder(executionOrder, autoOrder.getUserId(), currentPrice, autoOrder.getVolume(), tradeTotalAmt);

        if (success) {
            String sideKr = autoOrder.getSide().equals("BID") ? "매수" : "매도";
            String msg = String.format("[자동 매매 체결]\n목표가 도달로 %s %s가 완료되었습니다!", 
                                        autoOrder.getMarket().replace("KRW-", ""), sideKr);
            SwingUtilities.invokeLater(() -> NotificationUtil.showToast(mainFrame, msg));
        }
    }
}