package DAO;

import com.team.coin_simulator.DBConnection;
import DTO.OrderDTO;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

public class OrderDAO {
    public boolean insertOrder(OrderDTO order, String userId) {
    	String orderSql = "INSERT INTO orders (order_id, user_id, market, side, original_price, original_volume, remaining_volume, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        String assetSql = "UPDATE assets SET balance = balance - ?, locked = locked + ? WHERE user_id = ? AND currency = ?";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); 

            try (PreparedStatement pstmt = conn.prepareStatement(orderSql)) {
                //1번 물음표에 자바가 만든 ID를 넣습니다.
                pstmt.setLong(1, order.getOrderId()); 
                
                // 나머지는 순서가 하나씩 밀립니다.
                pstmt.setString(2, userId);
                pstmt.setString(3, "KRW-BTC"); 
                pstmt.setString(4, order.getSide());
                pstmt.setBigDecimal(5, order.getOriginalPrice());
                pstmt.setBigDecimal(6, order.getOriginalVolume());
                pstmt.setBigDecimal(7, order.getRemainingVolume());
                pstmt.setString(8, order.getStatus());
                pstmt.executeUpdate();
            }

            // 2. 잔고 업데이트 로직 완성
            try (PreparedStatement pstmt = conn.prepareStatement(assetSql)) {
                // 매수(BID)면 KRW 차감, 매도(ASK)면 코인 차감
                String currency = order.getSide().equals("BID") ? "KRW" : "BTC";
                BigDecimal amount = order.getSide().equals("BID") ? 
                        order.getOriginalPrice().multiply(order.getOriginalVolume()) : order.getOriginalVolume();

                pstmt.setBigDecimal(1, amount); // balance 차감액
                pstmt.setBigDecimal(2, amount); // locked 증가액
                pstmt.setString(3, userId);
                pstmt.setString(4, currency);
                
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("자산 정보가 없어 업데이트에 실패했습니다.");
                }
            }

            conn.commit(); //모든 작업이 성공해야 실제 DB에 기록됨
            System.out.println(">> [DB] 주문 및 자산 업데이트 완료 (Commit)");
            return true;

        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback(); //하나라도 실패하면 모두 되돌림
                    System.err.println(">> [DB] 오류 발생으로 롤백되었습니다.");
                } catch (SQLException ex) { ex.printStackTrace(); }
            }
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) {
                try { conn.close(); } catch (SQLException e) { e.printStackTrace(); }
            }
        }
    }
    
 // 1. 주문 취소 처리
    public boolean cancelOrder(long orderId, String userId, String side, BigDecimal amount) {
        String updateOrderSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND user_id = ?";
        String refundAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? WHERE user_id = ? AND currency = ?";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 트랜잭션 시작

            // 1) 주문 상태 변경 ('WAIT' -> 'CANCEL')
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setLong(1, orderId);
                pstmt.setString(2, userId);
                
                int affectedRows = pstmt.executeUpdate();
                // [핵심] 여기서 0이 나오면 "그런 주문 번호 없는데?" 라는 뜻
                if (affectedRows == 0) {
                    throw new SQLException("DB에서 해당 주문번호(" + orderId + ")를 찾을 수 없거나 이미 취소되었습니다.");
                }
            }

            // 2) 자산 복구 (Locked -> Balance)
            try (PreparedStatement pstmt = conn.prepareStatement(refundAssetSql)) {
                String currency = side.equals("BID") ? "KRW" : "BTC";
                pstmt.setBigDecimal(1, amount); // balance에 다시 더함
                pstmt.setBigDecimal(2, amount); // locked에서 뺌
                pstmt.setString(3, userId);
                pstmt.setString(4, currency);
                
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("자산 복구에 실패했습니다. (자산 데이터 없음)");
                }
            }

            conn.commit(); //커밋을 해야 Workbench에 반영됨
            System.out.println(">> [DB] 주문 취소 및 자산 복구 완료");
            return true;
            
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            System.err.println("취소 실패: " + e.getMessage());
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }

    // 2. 주문 정정 처리
    public boolean modifyOrder(long orderId, String userId, String side, BigDecimal oldAmount, BigDecimal newAmount, BigDecimal newPrice, BigDecimal newQty) {
        String updateOrderSql = "UPDATE orders SET original_price = ?, original_volume = ?, remaining_volume = ? WHERE order_id = ? AND user_id = ?";
        String updateAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? WHERE user_id = ? AND currency = ?";

        try (Connection conn = DBConnection.getConnection()) {
            conn.setAutoCommit(false);

            // 자산 변동분 계산 (기존 잠금 해제 후 새로운 금액 잠금)
            // 차이만큼만 조정 (기존금액 - 새금액)만큼 Balance에 더함 (새금액이 크면 마이너스가 되어 결국 차감됨)
            BigDecimal diff = oldAmount.subtract(newAmount);

            try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                String currency = side.equals("BID") ? "KRW" : "BTC";
                pstmt.setBigDecimal(1, diff);
                pstmt.setBigDecimal(2, diff.negate()); // Locked는 반대로 작동
                pstmt.setString(3, userId);
                pstmt.setString(4, currency);
                pstmt.executeUpdate();
            }

            // 주문 정보 수정
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setBigDecimal(1, newPrice);
                pstmt.setBigDecimal(2, newQty);
                pstmt.setBigDecimal(3, newQty);
                pstmt.setLong(4, orderId); // 이제 이 ID가 DB에 확실히 존재하게 됨
                pstmt.setString(5, userId);
                
                int affectedRows = pstmt.executeUpdate();
                //만약 ID가 달라서 업데이트가 안 됐다면 에러 발생시키기!
                if (affectedRows == 0) {
                    throw new SQLException("DB에서 해당 주문번호(" + orderId + ")를 찾을 수 없습니다.");
                }
            }

            conn.commit();
            System.out.println(">> [DB] 주문 정정 완료");
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    
 // 시장가 주문 (즉시 체결) 처리 메서드
    public boolean executeMarketOrder(OrderDTO order, String userId, BigDecimal tradePrice, BigDecimal tradeVolume, BigDecimal tradeTotalAmt) {
        // 1. 주문서 저장 (status: DONE)
        String insertOrderSql = "INSERT INTO orders (order_id, user_id, market, side, original_price, original_volume, remaining_volume, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        
        // 2. 체결 내역 저장 (설계도 반영: order_id, price, volume, fee, market, side, user_id, total_price)
        String insertExecSql = "INSERT INTO executions " +
                               "(order_id, price, volume, fee, market, side, user_id, total_price) " +
                               "VALUES (?, ?, ?, 0, ?, ?, ?, ?)"; // fee는 0으로 고정
        
        // 3. 자산 변경 쿼리
        String updateKrwSql = "UPDATE assets SET balance = balance + ? WHERE user_id = ? AND currency = 'KRW'";
        String updateCoinSql = "INSERT INTO assets (user_id, currency, balance, locked) VALUES (?, ?, ?, 0) " +
                               "ON DUPLICATE KEY UPDATE balance = balance + ?";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 트랜잭션 시작

            // [STEP 1] 주문 내역 저장
            try (PreparedStatement pstmt = conn.prepareStatement(insertOrderSql)) {
                pstmt.setLong(1, order.getOrderId());
                pstmt.setString(2, userId);
                pstmt.setString(3, "KRW-BTC");
                pstmt.setString(4, order.getSide());
                pstmt.setBigDecimal(5, tradePrice);
                pstmt.setBigDecimal(6, tradeVolume);
                pstmt.setBigDecimal(7, BigDecimal.ZERO);
                pstmt.setString(8, "DONE");
                pstmt.executeUpdate();
            }

            // [STEP 2] 체결 내역 저장 (설계도 완벽 매칭)
            try (PreparedStatement pstmt = conn.prepareStatement(insertExecSql)) {
                pstmt.setLong(1, order.getOrderId());     // order_id
                pstmt.setBigDecimal(2, tradePrice);       // price
                pstmt.setBigDecimal(3, tradeVolume);      // volume
                // fee는 쿼리에서 0으로 직접 입력했으므로 생략
                pstmt.setString(4, "KRW-BTC");            // market
                pstmt.setString(5, order.getSide());      // side (설계도에 있는 컬럼!)
                pstmt.setString(6, userId);               // user_id
                pstmt.setBigDecimal(7, tradeTotalAmt);    // total_price
                pstmt.executeUpdate();
            }

            // [STEP 3] 자산 업데이트 (기존 로직 동일)
            if (order.getSide().equals("BID")) { // 매수
                try (PreparedStatement pstmt = conn.prepareStatement(updateKrwSql)) {
                    pstmt.setBigDecimal(1, tradeTotalAmt.negate()); 
                    pstmt.setString(2, userId);
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement(updateCoinSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setString(2, "BTC");
                    pstmt.setBigDecimal(3, tradeVolume);
                    pstmt.setBigDecimal(4, tradeVolume);
                    pstmt.executeUpdate();
                }
            } else { // 매도
                try (PreparedStatement pstmt = conn.prepareStatement(updateCoinSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setString(2, "BTC");
                    pstmt.setBigDecimal(3, tradeVolume.negate());
                    pstmt.setBigDecimal(4, tradeVolume.negate());
                    pstmt.executeUpdate();
                }
                try (PreparedStatement pstmt = conn.prepareStatement(updateKrwSql)) {
                    pstmt.setBigDecimal(1, tradeTotalAmt); 
                    pstmt.setString(2, userId);
                    pstmt.executeUpdate();
                }
            }

            conn.commit();
            System.out.println(">> [DB] 시장가 체결 완료");
            
         // 알림 발송
            String sideKr = order.getSide().equals("BID") ? "매수" : "매도";
            // 시장가는 100% 체결이므로 "최종 체결" 메시지 구성
            String alertMsg = String.format("[%s] %s 주문이 최종 체결되었습니다. (단가: %,.0f)", 
                                            "비트코인", sideKr, tradePrice);
            
            // 메인 프레임이나 알림 센터로 이벤트 전달 (임시로 콘솔이나 Toast 호출)
            // 실제 적용 시 MainFrame.getInstance() 등을 통해 화면에 Toast를 띄움
            // NotificationUtil.showToast(mainFrame, alertMsg);
            System.out.println(alertMsg);

            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            System.err.println("DB 에러: " + e.getMessage()); // 에러 메시지 확인용
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
public List<OrderDTO> checkAndExecuteLimitOrders(String market, BigDecimal currentPrice) {
        
        // 웹소켓에서 "BTC"만 오면 DB 양식인 "KRW-BTC"로 변경
        if (!market.startsWith("KRW-")) {
            market = "KRW-" + market;
        }

        List<OrderDTO> executedOrders = new ArrayList<>();
        Connection conn = null;

        try {
            conn = com.team.coin_simulator.DBConnection.getConnection();
            conn.setAutoCommit(false); // 트랜잭션 시작

            String selectSql = "SELECT * FROM orders WHERE market = ? AND status = 'WAIT'";
            
            try (PreparedStatement pstmt = conn.prepareStatement(selectSql)) {
                pstmt.setString(1, market);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        long orderId = rs.getLong("order_id");
                        String userId = rs.getString("user_id");
                        String side = rs.getString("side");
                        BigDecimal targetPrice = rs.getBigDecimal("original_price");
                        BigDecimal volume = rs.getBigDecimal("original_volume");

                        boolean shouldExecute = false;

                        // 체결 조건 검사
                        if ("BID".equals(side) && currentPrice.compareTo(targetPrice) <= 0) {
                            shouldExecute = true;
                        } else if ("ASK".equals(side) && currentPrice.compareTo(targetPrice) >= 0) {
                            shouldExecute = true;
                        }

                        if (shouldExecute) {
                            // [1] 주문 상태 변경
                            try (PreparedStatement updateOrder = conn.prepareStatement(
                                    "UPDATE orders SET status = 'DONE', remaining_volume = 0 WHERE order_id = ?")) {
                                updateOrder.setLong(1, orderId);
                                updateOrder.executeUpdate();
                            }

                            // =======================================================
                            // 💡 [핵심] 아까 통째로 빠져있었던 진짜 지갑(assets) 업데이트 로직!
                            // =======================================================
                            BigDecimal totalOrderPrice = targetPrice.multiply(volume);
                            String coinSymbol = market.replace("KRW-", ""); // "BTC" 추출

                            if ("BID".equals(side)) {
                                // [매수] 원화 묶인돈(locked) 차감 -> 코인 잔고(balance) 증가
                                try (PreparedStatement updateKrw = conn.prepareStatement(
                                        "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND currency = 'KRW'")) {
                                    updateKrw.setBigDecimal(1, totalOrderPrice);
                                    updateKrw.setString(2, userId);
                                    updateKrw.executeUpdate();
                                }
                                try (PreparedStatement updateCoin = conn.prepareStatement(
                                        "INSERT INTO assets (user_id, currency, balance, locked) VALUES (?, ?, ?, 0) " +
                                        "ON DUPLICATE KEY UPDATE balance = balance + ?")) {
                                    updateCoin.setString(1, userId);
                                    updateCoin.setString(2, coinSymbol);
                                    updateCoin.setBigDecimal(3, volume);
                                    updateCoin.setBigDecimal(4, volume);
                                    updateCoin.executeUpdate();
                                }
                            } else {
                                // [매도] 코인 묶인돈(locked) 차감 -> 원화 잔고(balance) 증가
                                try (PreparedStatement updateCoin = conn.prepareStatement(
                                        "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND currency = ?")) {
                                    updateCoin.setBigDecimal(1, volume);
                                    updateCoin.setString(2, userId);
                                    updateCoin.setString(3, coinSymbol);
                                    updateCoin.executeUpdate();
                                }
                                try (PreparedStatement updateKrw = conn.prepareStatement(
                                        "UPDATE assets SET balance = balance + ? WHERE user_id = ? AND currency = 'KRW'")) {
                                    updateKrw.setBigDecimal(1, totalOrderPrice);
                                    updateKrw.setString(2, userId);
                                    updateKrw.executeUpdate();
                                }
                            }

                            // [3] 체결 내역(executions) 추가
                            try (PreparedStatement insertExec = conn.prepareStatement(
                                    "INSERT INTO executions (order_id, user_id, market, side, price, volume, total_price, fee) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, 0)")) {
                                insertExec.setLong(1, orderId);
                                insertExec.setString(2, userId);
                                insertExec.setString(3, market);
                                insertExec.setString(4, side);
                                insertExec.setBigDecimal(5, targetPrice);
                                insertExec.setBigDecimal(6, volume);
                                insertExec.setBigDecimal(7, totalOrderPrice);
                                insertExec.executeUpdate();
                            }

                            // 알림용 데이터 담기
                            OrderDTO executed = new OrderDTO();
                            executed.setOrderId(orderId);
                            executed.setSide(side);
                            executed.setOriginalPrice(targetPrice);
                            executed.setOriginalVolume(volume);
                            executedOrders.add(executed);
                        }
                    }
                }
            }
            conn.commit(); // 모두 성공 시 확정
        } catch (Exception e) {
            if (conn != null) try { conn.rollback(); } catch(Exception ex) {}
            e.printStackTrace();
        } finally {
            if (conn != null) try { conn.close(); } catch(Exception ex) {}
        }
        return executedOrders;
    }

//유저의 자산(Balance, Locked) 정보를 가져오는 메서드
public void getUserAssets(String userId, Map<String, BigDecimal> balanceMap, Map<String, BigDecimal> lockedMap) {
    String sql = "SELECT currency, balance, locked FROM assets WHERE user_id = ?";
    try (Connection conn = DBConnection.getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
        
        pstmt.setString(1, userId);
        try (ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                String curr = rs.getString("currency");
                balanceMap.put(curr, rs.getBigDecimal("balance"));
                lockedMap.put(curr, rs.getBigDecimal("locked"));
            }
        }
    } catch (SQLException e) {
        System.err.println("자산 정보 로드 실패: " + e.getMessage());
        e.printStackTrace();
    }
}

//유저의 미체결 대기 주문(WAIT) 목록을 가져오는 메서드
public List<OrderDTO> getOpenOrders(String userId) {
    List<OrderDTO> openOrders = new ArrayList<>();
    String sql = "SELECT * FROM orders WHERE user_id = ? AND status = 'WAIT'";
    
    try (Connection conn = DBConnection.getConnection();
         PreparedStatement pstmt = conn.prepareStatement(sql)) {
        
        pstmt.setString(1, userId);
        try (ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                OrderDTO order = new OrderDTO();
                order.setOrderId(rs.getLong("order_id"));
                order.setSide(rs.getString("side"));
                order.setOriginalPrice(rs.getBigDecimal("original_price"));
                order.setOriginalVolume(rs.getBigDecimal("original_volume"));
                order.setRemainingVolume(rs.getBigDecimal("remaining_volume"));
                order.setStatus(rs.getString("status"));
                order.setMarket(rs.getString("market")); // market 정보(예: KRW-BTC)도 DTO에 담습니다.
                
                openOrders.add(order);
            }
        }
    } catch (SQLException e) {
        System.err.println("미체결 주문 로드 실패: " + e.getMessage());
        e.printStackTrace();
    	}
    return openOrders;
	}
}