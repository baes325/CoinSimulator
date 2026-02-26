package DAO;

import com.team.coin_simulator.DBConnection;
import DTO.OrderDTO;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;
import java.math.RoundingMode; // 💡 평단가 나눗셈 반올림을 위해 추가!

public class OrderDAO {
    //수수료 정의
    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    
    //지정가 주문 (자산 잠금까지 완벽 처리)
    public boolean insertOrder(DTO.OrderDTO order) {
        String insertOrderSql = "INSERT INTO orders (order_id, user_id, session_id, market, side, type, original_price, original_volume, remaining_volume, status) " +
                                "VALUES (?, ?, ?, ?, ?, 'LIMIT', ?, ?, ?, 'WAIT')";
        
        // 지정가 주문 시 자산을 미리 묶어둠(Locked)
        String lockAssetSql = "UPDATE assets SET balance = balance - ?, locked = locked + ? " +
                "WHERE user_id = ? AND session_id = ? AND currency = ? AND balance >= ?";
        
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); 
             
            //주문 내역 저장
            try (PreparedStatement pstmt = conn.prepareStatement(insertOrderSql)) {
                pstmt.setLong(1, order.getOrderId());
                pstmt.setString(2, order.getUserId());
                pstmt.setLong(3, order.getSessionId()); 
                pstmt.setString(4, order.getMarket());
                pstmt.setString(5, order.getSide());
                pstmt.setBigDecimal(6, order.getOriginalPrice());
                pstmt.setBigDecimal(7, order.getOriginalVolume());
                pstmt.setBigDecimal(8, order.getOriginalVolume());
                pstmt.executeUpdate();
            }

            //자산 잠금 (KRW 또는 코인)
            String currency = order.getSide().equals("BID") ? "KRW" : order.getMarket().replace("KRW-", "");
            BigDecimal requiredAmt;
            if (order.getSide().equals("BID")) { // 매수: (가격 * 수량) + 0.05% 수수료
                BigDecimal orderCost = order.getOriginalPrice().multiply(order.getOriginalVolume());
                BigDecimal fee = orderCost.multiply(FEE_RATE);
                requiredAmt = orderCost.add(fee);
            } else { // 매도: 코인 수량만 묶음
                requiredAmt = order.getOriginalVolume();
            }
            
            try (PreparedStatement pstmt = conn.prepareStatement(lockAssetSql)) {
                pstmt.setBigDecimal(1, requiredAmt); // balance - ?
                pstmt.setBigDecimal(2, requiredAmt); // locked + ?
                pstmt.setString(3, order.getUserId()); // user_id = ?
                pstmt.setLong(4, order.getSessionId()); // session_id = ?
                pstmt.setString(5, currency); // currency = ?
                pstmt.setBigDecimal(6, requiredAmt); // balance >= ? (잔고 검사!)
                
                //만약 잔고가 부족해서 업데이트가 안 됐다면 튕겨내기
                if (pstmt.executeUpdate() == 0) {
                    throw new SQLException("잔고 부족");
                }
            }
            
            conn.commit(); //모든 작업이 성공해야 실제 DB에 기록됨
            System.out.println(">> [DB] 주문 및 자산 업데이트 완료 (Commit)");
            return true;
            
        } catch (Exception e) {
            if (conn != null) {
                try { 
                    conn.rollback(); //하나라도 실패하면 모두 되돌림
                    System.err.println(">> [DB] 오류 발생으로 롤백되었습니다.");
                } catch(SQLException ex) { ex.printStackTrace(); }
            }
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
    //주문 취소 처리 (어떤 코인이든 알아서 환불)
    public boolean cancelOrder(long orderId, String userId, String side, BigDecimal amount) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            String updateOrderSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND user_id = ? AND status = 'WAIT'";
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setLong(1, orderId);
                pstmt.setString(2, userId);
                
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("이미 처리된 주문이거나 취소할 수 없는 상태입니다.");
                }
            }

            long sessionId = 0;
            String market = "";
            String fetchSql = "SELECT session_id, market FROM orders WHERE order_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(fetchSql)) {
                pstmt.setLong(1, orderId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        sessionId = rs.getLong("session_id");
                        market = rs.getString("market");
                    }
                }
            }

            String currency = side.equals("BID") ? "KRW" : market.replace("KRW-", "");
            
            // 🚀 [핵심 수술 지점] 파라미터로 넘어온 amount의 미세한 소수점 먼지를 버림(DOWN) 처리!
            BigDecimal safeAmount = amount.setScale(8, java.math.RoundingMode.DOWN);

            String refundAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? " +
                                    "WHERE user_id = ? AND session_id = ? AND currency = ? AND locked >= ?";

            try (PreparedStatement pstmt = conn.prepareStatement(refundAssetSql)) {
                // 💡 amount 대신 먼지를 털어낸 safeAmount를 넣어줍니다!
                pstmt.setBigDecimal(1, safeAmount);
                pstmt.setBigDecimal(2, safeAmount);
                pstmt.setString(3, userId);
                pstmt.setLong(4, sessionId);
                pstmt.setString(5, currency);
                pstmt.setBigDecimal(6, safeAmount); 

                if (pstmt.executeUpdate() == 0) {
                    throw new SQLException("자산 복구에 실패했습니다. (금고 부족 / 요청액: " + safeAmount + ")");
                }
            }

            conn.commit();
            System.out.println(">> [DB] 취소 성공: " + orderId);
            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            System.err.println(">> [취소 중단] " + e.getMessage());
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ex) {}
        }
    }

    //주문 정정 처리
    public boolean modifyOrder(long orderId, String userId, String side, BigDecimal oldAmount, BigDecimal newAmount, BigDecimal newPrice, BigDecimal newQty) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            long sessionId = 0;
            String market = "";
            String fetchSql = "SELECT session_id, market FROM orders WHERE order_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(fetchSql)) {
                pstmt.setLong(1, orderId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        sessionId = rs.getLong("session_id");
                        market = rs.getString("market");
                    } else {
                        throw new SQLException("주문 정보를 찾을 수 없습니다.");
                    }
                }
            }

            BigDecimal diff = oldAmount.subtract(newAmount); 
            String currency = side.equals("BID") ? "KRW" : market.replace("KRW-", "");

            if (diff.compareTo(BigDecimal.ZERO) != 0) {
                String updateAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = ?";
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setBigDecimal(1, diff);
                    pstmt.setBigDecimal(2, diff); 
                    pstmt.setString(3, userId);
                    pstmt.setLong(4, sessionId);
                    pstmt.setString(5, currency);
                    
                    if (pstmt.executeUpdate() == 0) {
                        throw new SQLException("정정할 자산 계좌를 찾지 못했습니다.");
                    }
                }
            } 

            String updateOrderSql = "UPDATE orders SET original_price = ?, original_volume = ?, remaining_volume = ? WHERE order_id = ? AND user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setBigDecimal(1, newPrice);
                pstmt.setBigDecimal(2, newQty);
                pstmt.setBigDecimal(3, newQty);
                pstmt.setLong(4, orderId); 
                pstmt.setString(5, userId);
                
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    throw new SQLException("DB에서 해당 주문번호(" + orderId + ")를 찾을 수 없습니다.");
                }
            }

            conn.commit();
            System.out.println(">> [DB] 주문 정정 완료");
            return true;
            
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
    //시장가 주문 (즉시 체결 + 평단가 갱신 + 손익 계산 추가!)
    public boolean executeMarketOrder(OrderDTO order, String userId, BigDecimal tradePrice, BigDecimal tradeVolume, BigDecimal tradeTotalAmt) {
        String insertOrderSql = "INSERT INTO orders (order_id, user_id, session_id, market, side, type, original_price, original_volume, remaining_volume, status) " +
                                "VALUES (?, ?, ?, ?, ?, 'MARKET', ?, ?, ?, 'DONE')";
        
        //평단가, 손익, 수익률 컬럼 추가!
        String insertExecSql = "INSERT INTO executions (order_id, price, volume, fee, market, side, user_id, total_price, buy_avg_price, realized_pnl, roi) " +
                               "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"; 
        
        //평단가도 함께 업데이트되도록 변경!
        String updateAssetSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked, avg_buy_price) VALUES (?, ?, ?, ?, 0, ?) " +
                "ON DUPLICATE KEY UPDATE balance = balance + ?, avg_buy_price = ?";
        
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);
            
            BigDecimal fee = tradeTotalAmt.multiply(FEE_RATE);
            String symbol = order.getMarket().replace("KRW-", ""); 

            //1. 기존 평단가 및 잔고 조회 (정산 계산을 위함)
            BigDecimal currentCoinBal = BigDecimal.ZERO;
            BigDecimal currentAvgPrice = BigDecimal.ZERO;
            String fetchAssetSql = "SELECT balance, avg_buy_price FROM assets WHERE user_id = ? AND session_id = ? AND currency = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(fetchAssetSql)) {
                pstmt.setString(1, userId);
                pstmt.setLong(2, order.getSessionId());
                pstmt.setString(3, symbol);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        currentCoinBal = rs.getBigDecimal("balance");
                        if (rs.getBigDecimal("avg_buy_price") != null) {
                            currentAvgPrice = rs.getBigDecimal("avg_buy_price"); // 🚀 [수정]
                        }
                    }
                }
            }

            // 💡 [추가] 2. 정산 로직 가동!
            BigDecimal newAvgPrice = currentAvgPrice;
            BigDecimal realizedPnl = null;
            BigDecimal roi = null;

            if (order.getSide().equals("BID")) {
                // 매수: 가중평균 평단가 계산
                BigDecimal oldTotalValue = currentCoinBal.multiply(currentAvgPrice);
                BigDecimal newTotalValue = tradeVolume.multiply(tradePrice); // 순수 코인 가치 (수수료 제외)
                BigDecimal newTotalBal = currentCoinBal.add(tradeVolume);
                
                if (newTotalBal.compareTo(BigDecimal.ZERO) > 0) {
                    newAvgPrice = oldTotalValue.add(newTotalValue).divide(newTotalBal, 8, RoundingMode.HALF_UP);
                }
            } else {
                // 매도: 실현 손익(PNL) 및 수익률(ROI) 계산
                if (currentAvgPrice.compareTo(BigDecimal.ZERO) > 0) {
                    // PNL = (매도가 - 평단가) * 수량 - 수수료
                    realizedPnl = tradePrice.subtract(currentAvgPrice).multiply(tradeVolume).subtract(fee);
                    // ROI(%) = (매도가 - 평단가) / 평단가 * 100
                    roi = tradePrice.subtract(currentAvgPrice).divide(currentAvgPrice, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
                } else {
                    realizedPnl = BigDecimal.ZERO;
                    roi = BigDecimal.ZERO;
                }
            }

            // [1] 주문 내역 저장
            try (PreparedStatement pstmt = conn.prepareStatement(insertOrderSql)) {
                pstmt.setLong(1, order.getOrderId()); pstmt.setString(2, userId); pstmt.setLong(3, order.getSessionId()); 
                pstmt.setString(4, order.getMarket()); pstmt.setString(5, order.getSide());
                pstmt.setBigDecimal(6, tradePrice); pstmt.setBigDecimal(7, tradeVolume); pstmt.setBigDecimal(8, BigDecimal.ZERO);
                pstmt.executeUpdate();
            }
            
            // [2] 체결 내역 저장 (평단가, PNL, ROI 추가 등록!)
            try (PreparedStatement pstmt = conn.prepareStatement(insertExecSql)) {
                pstmt.setLong(1, order.getOrderId()); pstmt.setBigDecimal(2, tradePrice); pstmt.setBigDecimal(3, tradeVolume);      
                pstmt.setBigDecimal(4, fee); pstmt.setString(5, order.getMarket()); pstmt.setString(6, order.getSide()); pstmt.setString(7, userId);               
                pstmt.setBigDecimal(8, tradeTotalAmt);    
                // 🚀 빈칸이었던 데이터 채우기!
                pstmt.setBigDecimal(9, newAvgPrice); 
                pstmt.setObject(10, realizedPnl, Types.DECIMAL); // null 허용 삽입
                pstmt.setObject(11, roi, Types.DECIMAL);         // null 허용 삽입
                pstmt.executeUpdate();
            }

            // [3] 자산 업데이트
            if (order.getSide().equals("BID")) { // 매수
                BigDecimal totalDeduct = tradeTotalAmt.add(fee);
                // KRW 차감
                String deductKrwSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked, avg_buy_price) VALUES (?, ?, ?, ?, 0, 0) ON DUPLICATE KEY UPDATE balance = balance + ?";
                try (PreparedStatement pstmt = conn.prepareStatement(deductKrwSql)) {
                    pstmt.setString(1, userId); pstmt.setLong(2, order.getSessionId()); pstmt.setString(3, "KRW");
                    pstmt.setBigDecimal(4, totalDeduct.negate()); pstmt.setBigDecimal(5, totalDeduct.negate());
                    pstmt.executeUpdate();
                }
                // 코인 획득 및 평단가 갱신!
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId); pstmt.setLong(2, order.getSessionId()); pstmt.setString(3, symbol);
                    pstmt.setBigDecimal(4, tradeVolume); pstmt.setBigDecimal(5, newAvgPrice); // Insert 시 평단가
                    pstmt.setBigDecimal(6, tradeVolume); pstmt.setBigDecimal(7, newAvgPrice); // Update 시 평단가
                    pstmt.executeUpdate();
                }
            } else { // 매도
                BigDecimal totalEarned = tradeTotalAmt.subtract(fee); 
                // 코인 차감 (매도는 평단가 변동 없음)
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId); pstmt.setLong(2, order.getSessionId()); pstmt.setString(3, symbol);
                    pstmt.setBigDecimal(4, tradeVolume.negate()); pstmt.setBigDecimal(5, currentAvgPrice);
                    pstmt.setBigDecimal(6, tradeVolume.negate()); pstmt.setBigDecimal(7, currentAvgPrice);
                    pstmt.executeUpdate();
                }
                // KRW 획득
                String addKrwSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked, avg_buy_price) VALUES (?, ?, ?, ?, 0, 0) ON DUPLICATE KEY UPDATE balance = balance + ?";
                try (PreparedStatement pstmt = conn.prepareStatement(addKrwSql)) {
                    pstmt.setString(1, userId); pstmt.setLong(2, order.getSessionId()); pstmt.setString(3, "KRW");
                    pstmt.setBigDecimal(4, totalEarned); pstmt.setBigDecimal(5, totalEarned);
                    pstmt.executeUpdate();
                }
            }

            conn.commit();
            System.out.println(">> [DB] 시장가 체결 및 정산 완료! (PNL: " + realizedPnl + ")");
            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
    // 자동 체결 검사 및 실행 (백테스팅/지정가용) - 정산 로직 탑재!
    public List<OrderDTO> checkAndExecuteLimitOrders(String market, BigDecimal currentRealPrice, BigDecimal currentTradeVolume, long sessionId) {
        if (!market.startsWith("KRW-")) market = "KRW-" + market;

        List<OrderDTO> executedList = new ArrayList<>();
        Connection conn = null;
        
        String bidSql = "SELECT * FROM orders WHERE market = ? AND session_id = ? AND status = 'WAIT' AND side = 'BID' AND original_price >= ? ORDER BY original_price DESC, order_id ASC";
        String askSql = "SELECT * FROM orders WHERE market = ? AND session_id = ? AND status = 'WAIT' AND side = 'ASK' AND original_price <= ? ORDER BY original_price ASC, order_id ASC";

        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            BigDecimal availableVolumeForBid = currentTradeVolume;
            BigDecimal availableVolumeForAsk = currentTradeVolume;

            try (PreparedStatement bidPstmt = conn.prepareStatement(bidSql)) {
                bidPstmt.setString(1, market);
                bidPstmt.setLong(2, sessionId); 
                bidPstmt.setBigDecimal(3, currentRealPrice);
                try (ResultSet rs = bidPstmt.executeQuery()) {
                    while (rs.next() && availableVolumeForBid.compareTo(BigDecimal.ZERO) > 0) {
                        OrderDTO order = mapResultSetToOrderDTO(rs);
                        availableVolumeForBid = processPartialExecution(conn, order, executedList, market, "BID", availableVolumeForBid);
                    }
                }
            }

            try (PreparedStatement askPstmt = conn.prepareStatement(askSql)) {
                askPstmt.setString(1, market);
                askPstmt.setLong(2, sessionId); 
                askPstmt.setBigDecimal(3, currentRealPrice);
                try (ResultSet rs = askPstmt.executeQuery()) {
                    while (rs.next() && availableVolumeForAsk.compareTo(BigDecimal.ZERO) > 0) {
                        OrderDTO order = mapResultSetToOrderDTO(rs);
                        availableVolumeForAsk = processPartialExecution(conn, order, executedList, market, "ASK", availableVolumeForAsk);
                    }
                }
            }
            conn.commit(); 
        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            System.err.println(">> [자동 체결 오류] " + e.getMessage());
        } finally {
            if (conn != null) try { conn.close(); } catch (Exception ex) {}
        }

        return executedList;
    }

    // 💡 [핵심] 지정가 부분 체결 + 정산 로직
    private BigDecimal processPartialExecution(Connection conn, OrderDTO order, List<OrderDTO> executedList, String market, String expectedSide, BigDecimal availableTradeVolume) throws SQLException {
        
        BigDecimal orderRemainingVol = order.getRemainingVolume();
        BigDecimal executeVol = orderRemainingVol.min(availableTradeVolume);

        if (executeVol.compareTo(BigDecimal.ZERO) <= 0) return availableTradeVolume;

        String updateOrderSql = "UPDATE orders SET " +
                "status = CASE WHEN remaining_volume - ? <= 0 THEN 'DONE' ELSE 'WAIT' END, " +
                "remaining_volume = remaining_volume - ? " +
                "WHERE order_id = ? AND status = 'WAIT' AND remaining_volume >= ?";
        
        try (PreparedStatement updateStmt = conn.prepareStatement(updateOrderSql)) {
            updateStmt.setBigDecimal(1, executeVol);
            updateStmt.setBigDecimal(2, executeVol);
            updateStmt.setLong(3, order.getOrderId());
            updateStmt.setBigDecimal(4, executeVol); 
            if (updateStmt.executeUpdate() == 0) return availableTradeVolume; 
        }

        BigDecimal executionPrice = order.getOriginalPrice(); // 지정가 체결은 본인이 걸어둔 가격(original_price)에 체결됨
        BigDecimal totalExecutionCost = executionPrice.multiply(executeVol);
        BigDecimal fee = totalExecutionCost.multiply(FEE_RATE);
        String coinSymbol = market.replace("KRW-", ""); 

        //1. 기존 평단가 및 잔고 조회
        BigDecimal currentCoinBal = BigDecimal.ZERO;
        BigDecimal currentAvgPrice = BigDecimal.ZERO;
        String fetchAssetSql = "SELECT balance, avg_buy_price FROM assets WHERE user_id = ? AND session_id = ? AND currency = ?";
        try (PreparedStatement pstmt = conn.prepareStatement(fetchAssetSql)) {
            pstmt.setString(1, order.getUserId());
            pstmt.setLong(2, order.getSessionId());
            pstmt.setString(3, coinSymbol);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    currentCoinBal = rs.getBigDecimal("balance");
                    if (rs.getBigDecimal("avg_buy_price") != null) currentAvgPrice = rs.getBigDecimal("avg_buy_price"); // 🚀 [수정]
                }
            }
        }

        //2. 정산 로직 가동!
        BigDecimal newAvgPrice = currentAvgPrice;
        BigDecimal realizedPnl = null;
        BigDecimal roi = null;

        if ("BID".equals(expectedSide)) {
            BigDecimal oldTotalValue = currentCoinBal.multiply(currentAvgPrice);
            BigDecimal newTotalValue = executeVol.multiply(executionPrice); 
            BigDecimal newTotalBal = currentCoinBal.add(executeVol);
            
            if (newTotalBal.compareTo(BigDecimal.ZERO) > 0) {
                newAvgPrice = oldTotalValue.add(newTotalValue).divide(newTotalBal, 8, RoundingMode.HALF_UP);
            }

            BigDecimal deductAmt = totalExecutionCost.add(fee);
            String deductLockedSql = "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = 'KRW' AND locked >= ?";
            try (PreparedStatement dStmt = conn.prepareStatement(deductLockedSql)) {
                dStmt.setBigDecimal(1, deductAmt); dStmt.setString(2, order.getUserId()); dStmt.setLong(3, order.getSessionId()); dStmt.setBigDecimal(4, deductAmt);
                dStmt.executeUpdate();
            }
            //코인 추가 시 평단가 반영
            String addCoinSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked, avg_buy_price) VALUES (?, ?, ?, ?, 0, ?) " +
                                "ON DUPLICATE KEY UPDATE balance = balance + ?, avg_buy_price = ?";
            try (PreparedStatement aStmt = conn.prepareStatement(addCoinSql)) {
                aStmt.setString(1, order.getUserId()); aStmt.setLong(2, order.getSessionId()); aStmt.setString(3, coinSymbol); 
                aStmt.setBigDecimal(4, executeVol); aStmt.setBigDecimal(5, newAvgPrice); 
                aStmt.setBigDecimal(6, executeVol); aStmt.setBigDecimal(7, newAvgPrice);
                aStmt.executeUpdate();
            }
        } else {
            if (currentAvgPrice.compareTo(BigDecimal.ZERO) > 0) {
                realizedPnl = executionPrice.subtract(currentAvgPrice).multiply(executeVol).subtract(fee);
                roi = executionPrice.subtract(currentAvgPrice).divide(currentAvgPrice, 8, RoundingMode.HALF_UP).multiply(new BigDecimal("100"));
            } else {
                realizedPnl = BigDecimal.ZERO; roi = BigDecimal.ZERO;
            }

            // 코인 차감 (매도는 평단가 유지)
            String deductLockedSql = "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = ? AND locked >= ?";
            try (PreparedStatement dStmt = conn.prepareStatement(deductLockedSql)) {
                dStmt.setBigDecimal(1, executeVol); dStmt.setString(2, order.getUserId()); dStmt.setLong(3, order.getSessionId()); dStmt.setString(4, coinSymbol); dStmt.setBigDecimal(5, executeVol);
                dStmt.executeUpdate();
            }
            BigDecimal earnedKrw = totalExecutionCost.subtract(fee);
            String addKrwSql = "UPDATE assets SET balance = balance + ? WHERE user_id = ? AND session_id = ? AND currency = 'KRW'";
            try (PreparedStatement aStmt = conn.prepareStatement(addKrwSql)) {
                aStmt.setBigDecimal(1, earnedKrw); aStmt.setString(2, order.getUserId()); aStmt.setLong(3, order.getSessionId());
                aStmt.executeUpdate();
            }
        }

        // 4. 영수증 발급 (평단가, PNL, ROI 반영)
        String insertExecSql = "INSERT INTO executions (order_id, user_id, market, side, price, volume, total_price, fee, buy_avg_price, realized_pnl, roi) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement insertExec = conn.prepareStatement(insertExecSql)) {
            insertExec.setLong(1, order.getOrderId()); insertExec.setString(2, order.getUserId()); insertExec.setString(3, market); insertExec.setString(4, expectedSide);
            insertExec.setBigDecimal(5, executionPrice); insertExec.setBigDecimal(6, executeVol); insertExec.setBigDecimal(7, totalExecutionCost);
            insertExec.setBigDecimal(8, fee);
            insertExec.setBigDecimal(9, newAvgPrice); 
            insertExec.setObject(10, realizedPnl, Types.DECIMAL); 
            insertExec.setObject(11, roi, Types.DECIMAL);         
            insertExec.executeUpdate();
        }

        OrderDTO partialExecOrder = new OrderDTO();
        partialExecOrder.setOrderId(order.getOrderId());
        partialExecOrder.setSide(expectedSide);
        partialExecOrder.setOriginalPrice(executionPrice);
        partialExecOrder.setOriginalVolume(executeVol); 
        executedList.add(partialExecOrder);

        return availableTradeVolume.subtract(executeVol);
    }

    private OrderDTO mapResultSetToOrderDTO(ResultSet rs) throws SQLException {
        OrderDTO order = new OrderDTO();
        order.setOrderId(rs.getLong("order_id"));
        order.setUserId(rs.getString("user_id"));
        order.setSessionId(rs.getLong("session_id"));
        order.setMarket(rs.getString("market"));
        order.setSide(rs.getString("side"));
        order.setOriginalPrice(rs.getBigDecimal("original_price"));
        order.setOriginalVolume(rs.getBigDecimal("original_volume"));
        
        BigDecimal remainingVol = rs.getBigDecimal("remaining_volume");
        order.setRemainingVolume(remainingVol != null ? remainingVol : rs.getBigDecimal("original_volume"));
        order.setStatus(rs.getString("status"));
        return order;
    }

    @Deprecated
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
            e.printStackTrace();
        }
    }

    public List<OrderDTO> getOpenOrders(String userId, long sessionId) {
        List<OrderDTO> openOrders = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE user_id = ? AND session_id = ? AND status = 'WAIT'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId); 
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    OrderDTO order = new OrderDTO();
                    order.setOrderId(rs.getLong("order_id"));
                    order.setSide(rs.getString("side"));
                    order.setOriginalPrice(rs.getBigDecimal("original_price"));
                    order.setOriginalVolume(rs.getBigDecimal("original_volume"));
                    order.setRemainingVolume(rs.getBigDecimal("remaining_volume"));
                    order.setStatus(rs.getString("status"));
                    order.setMarket(rs.getString("market")); 
                    openOrders.add(order);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return openOrders;
    }
}