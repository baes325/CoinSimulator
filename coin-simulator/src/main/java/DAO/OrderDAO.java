package DAO;

import com.team.coin_simulator.DBConnection;
import DTO.OrderDTO;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

public class OrderDAO {
    
    //지정가 주문 (자산 잠금까지 완벽 처리)
    public boolean insertOrder(DTO.OrderDTO order) {
        String insertOrderSql = "INSERT INTO orders (order_id, user_id, session_id, market, side, type, original_price, original_volume, remaining_volume, status) " +
                                "VALUES (?, ?, ?, ?, ?, 'LIMIT', ?, ?, ?, 'WAIT')";
        
        // 지정가 주문 시 자산을 미리 묶어둠(Locked)
        String lockAssetSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked) VALUES (?, ?, ?, ?, ?) " +
                              "ON DUPLICATE KEY UPDATE balance = balance - ?, locked = locked + ?";

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
            BigDecimal requiredAmt = order.getSide().equals("BID") ? order.getOriginalPrice().multiply(order.getOriginalVolume()) : order.getOriginalVolume();

            try (PreparedStatement pstmt = conn.prepareStatement(lockAssetSql)) {
                pstmt.setString(1, order.getUserId());
                pstmt.setLong(2, order.getSessionId());
                pstmt.setString(3, currency);
                pstmt.setBigDecimal(4, requiredAmt.negate());
                pstmt.setBigDecimal(5, requiredAmt);
                pstmt.setBigDecimal(6, requiredAmt); // update 차감용
                pstmt.setBigDecimal(7, requiredAmt); // update 증가용
                pstmt.executeUpdate();
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

            // 1. 주문 상태 변경 (상태가 'WAIT'인 경우에만 'CANCEL'로 변경 가능)
            // 💡 여기서 'WAIT' 조건을 걸어야 중복 취소를 원천 차단합니다.
            String updateOrderSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND user_id = ? AND status = 'WAIT'";
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setLong(1, orderId);
                pstmt.setString(2, userId);
                
                int affectedRows = pstmt.executeUpdate();
                if (affectedRows == 0) {
                    // 이미 취소되었거나, 체결되었거나, 주문이 없는 경우
                    throw new SQLException("이미 처리된 주문이거나 취소할 수 없는 상태입니다.");
                }
            }

            // 2. 자산 복구
            // 💡 주문 정보에서 읽어온 진짜 session_id와 market을 사용하기 위해 먼저 조회
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
            // 💡 locked가 부족하면 환불 안 되게 한 번 더 방어
            String refundAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? " +
                                    "WHERE user_id = ? AND session_id = ? AND currency = ? AND locked >= ?";

            try (PreparedStatement pstmt = conn.prepareStatement(refundAssetSql)) {
                pstmt.setBigDecimal(1, amount);
                pstmt.setBigDecimal(2, amount);
                pstmt.setString(3, userId);
                pstmt.setLong(4, sessionId);
                pstmt.setString(5, currency);
                pstmt.setBigDecimal(6, amount); // 6번째 파라미터: locked >= amount

                if (pstmt.executeUpdate() == 0) {
                    throw new SQLException("자산 복구에 실패했습니다. (금고에 돈이 부족함)");
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

            // [1] 정정할 주문의 session_id와 market 조회
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

            // [2] 자산 변경 (차액만큼 알아서 더하고 빼기)
            String updateAssetSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                pstmt.setBigDecimal(1, diff);
                pstmt.setBigDecimal(2, diff); 
                pstmt.setString(3, userId);
                pstmt.setLong(4, sessionId);
                pstmt.setString(5, currency);
                
                // 💡 [핵심 수정] 0건 업데이트 방어
                if (pstmt.executeUpdate() == 0) {
                    throw new SQLException("정정할 자산 계좌를 찾지 못했습니다.");
                }
            }

            // [3] 주문 정보 수정
            String updateOrderSql = "UPDATE orders SET original_price = ?, original_volume = ?, remaining_volume = ? WHERE order_id = ? AND user_id = ?";
            try (PreparedStatement pstmt = conn.prepareStatement(updateOrderSql)) {
                pstmt.setBigDecimal(1, newPrice);
                pstmt.setBigDecimal(2, newQty);
                pstmt.setBigDecimal(3, newQty);
                pstmt.setLong(4, orderId); 
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
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
    //시장가 주문 (즉시 체결)
    public boolean executeMarketOrder(OrderDTO order, String userId, BigDecimal tradePrice, BigDecimal tradeVolume, BigDecimal tradeTotalAmt) {
        String insertOrderSql = "INSERT INTO orders (order_id, user_id, session_id, market, side, type, original_price, original_volume, remaining_volume, status) " +
                                "VALUES (?, ?, ?, ?, ?, 'MARKET', ?, ?, ?, 'DONE')";
        
        String insertExecSql = "INSERT INTO executions (order_id, price, volume, fee, market, side, user_id, total_price) " +
                               "VALUES (?, ?, ?, 0, ?, ?, ?, ?)"; 
        
        // UPSERT 구문으로 해당 세션의 자산이 없으면 생성하고, 있으면 더함
        String updateAssetSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked) VALUES (?, ?, ?, ?, 0) " +
                                "ON DUPLICATE KEY UPDATE balance = balance + ?";

        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            // [1] 주문 내역 저장
            try (PreparedStatement pstmt = conn.prepareStatement(insertOrderSql)) {
                pstmt.setLong(1, order.getOrderId());
                pstmt.setString(2, userId);
                pstmt.setLong(3, order.getSessionId()); 
                pstmt.setString(4, order.getMarket()); 
                pstmt.setString(5, order.getSide());
                pstmt.setBigDecimal(6, tradePrice);
                pstmt.setBigDecimal(7, tradeVolume);
                pstmt.setBigDecimal(8, BigDecimal.ZERO);
                pstmt.executeUpdate();
            }

            // [2] 체결 내역 저장
            try (PreparedStatement pstmt = conn.prepareStatement(insertExecSql)) {
                pstmt.setLong(1, order.getOrderId());     
                pstmt.setBigDecimal(2, tradePrice);       
                pstmt.setBigDecimal(3, tradeVolume);      
                pstmt.setString(4, order.getMarket());     
                pstmt.setString(5, order.getSide());      
                pstmt.setString(6, userId);               
                pstmt.setBigDecimal(7, tradeTotalAmt);    
                pstmt.executeUpdate();
            }

            // [3] 자산 업데이트 (KRW와 코인 각각 처리)
            String symbol = order.getMarket().replace("KRW-", ""); 
            
            if (order.getSide().equals("BID")) { // 매수
                // KRW 차감
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setLong(2, order.getSessionId());
                    pstmt.setString(3, "KRW");
                    pstmt.setBigDecimal(4, tradeTotalAmt.negate());
                    pstmt.setBigDecimal(5, tradeTotalAmt.negate());
                    pstmt.executeUpdate();
                }
                // 코인 획득
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setLong(2, order.getSessionId());
                    pstmt.setString(3, symbol);
                    pstmt.setBigDecimal(4, tradeVolume);
                    pstmt.setBigDecimal(5, tradeVolume);
                    pstmt.executeUpdate();
                }
            } else { // 매도
                // 코인 차감
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setLong(2, order.getSessionId());
                    pstmt.setString(3, symbol);
                    pstmt.setBigDecimal(4, tradeVolume.negate());
                    pstmt.setBigDecimal(5, tradeVolume.negate());
                    pstmt.executeUpdate();
                }
                // KRW 획득
                try (PreparedStatement pstmt = conn.prepareStatement(updateAssetSql)) {
                    pstmt.setString(1, userId);
                    pstmt.setLong(2, order.getSessionId());
                    pstmt.setString(3, "KRW");
                    pstmt.setBigDecimal(4, tradeTotalAmt);
                    pstmt.setBigDecimal(5, tradeTotalAmt);
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
            System.out.println(alertMsg);

            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch(SQLException ex) {}
            e.printStackTrace();
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch(SQLException ex) {}
        }
    }
    
    // 자동 체결 검사 및 실행 (백테스팅용)
    /**
     * [업그레이드된 자동 체결 엔진]
     * 실제 거래소의 체결 원칙 (가격 우선 -> 시간 우선)을 엄격하게 적용하여
     * 현재 시세와 교차(Cross)되는 주문을 찾아 체결시킵니다.
     */
public List<OrderDTO> checkAndExecuteLimitOrders(String market, BigDecimal currentRealPrice, BigDecimal currentTradeVolume, long sessionId) {
        
        if (!market.startsWith("KRW-")) {
            market = "KRW-" + market;
        }

        List<OrderDTO> executedList = new ArrayList<>();
        Connection conn = null;
        
        // SQL 쿼리에 session_id = ? 조건을 반드시 추가!
        String bidSql = "SELECT * FROM orders WHERE market = ? AND session_id = ? AND status = 'WAIT' AND side = 'BID' AND original_price >= ? ORDER BY original_price DESC, order_id ASC";
        String askSql = "SELECT * FROM orders WHERE market = ? AND session_id = ? AND status = 'WAIT' AND side = 'ASK' AND original_price <= ? ORDER BY original_price ASC, order_id ASC";

        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            BigDecimal availableVolumeForBid = currentTradeVolume;
            BigDecimal availableVolumeForAsk = currentTradeVolume;

            // [1단계] 매수(BID)
            try (PreparedStatement bidPstmt = conn.prepareStatement(bidSql)) {
                bidPstmt.setString(1, market);
                bidPstmt.setLong(2, sessionId); // 💡 세션 ID 세팅
                bidPstmt.setBigDecimal(3, currentRealPrice);
                try (ResultSet rs = bidPstmt.executeQuery()) {
                    while (rs.next() && availableVolumeForBid.compareTo(BigDecimal.ZERO) > 0) {
                        OrderDTO order = mapResultSetToOrderDTO(rs);
                        availableVolumeForBid = processPartialExecution(conn, order, executedList, market, "BID", availableVolumeForBid);
                    }
                }
            }

            // [2단계] 매도(ASK)
            try (PreparedStatement askPstmt = conn.prepareStatement(askSql)) {
                askPstmt.setString(1, market);
                askPstmt.setLong(2, sessionId); // 💡 세션 ID 세팅
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
            if (conn != null) try { conn.close(); } catch (SQLException ex) {}
        }

        return executedList;
    }

    // 💡 [핵심] 부분 체결 로직 (남은 시장 물량을 반환합니다)
    private BigDecimal processPartialExecution(Connection conn, OrderDTO order, List<OrderDTO> executedList, String market, String expectedSide, BigDecimal availableTradeVolume) throws SQLException {
        
        // 1. 내가 필요한 수량(remaining)과 시장에 풀린 수량(available) 중 '더 작은 것'을 이번 체결량으로 정합니다!
        BigDecimal orderRemainingVol = order.getRemainingVolume();
        BigDecimal executeVol = orderRemainingVol.min(availableTradeVolume);

        if (executeVol.compareTo(BigDecimal.ZERO) <= 0) return availableTradeVolume;

        // 2. 주문 상태 업데이트 (🔥 동시성 제어 + 부분 체결 계산 로직)
        // 남은 수량에서 방금 체결한 만큼 빼고, 만약 그 결과가 0 이하면 'DONE', 아니면 여전히 'WAIT' 유지
        String updateOrderSql = "UPDATE orders SET " +
                "status = CASE WHEN remaining_volume - ? <= 0 THEN 'DONE' ELSE 'WAIT' END, " +
                "remaining_volume = remaining_volume - ? " +
                "WHERE order_id = ? AND status = 'WAIT' AND remaining_volume >= ?";
        
        try (PreparedStatement updateStmt = conn.prepareStatement(updateOrderSql)) {
            updateStmt.setBigDecimal(1, executeVol);
            updateStmt.setBigDecimal(2, executeVol);
            updateStmt.setLong(3, order.getOrderId());
            updateStmt.setBigDecimal(4, executeVol); // 동시성 방어 (내 체결량보다 남은게 많을 때만 성공)
            
            if (updateStmt.executeUpdate() == 0) return availableTradeVolume; // 다른 스레드가 채갔으면 패스
        }

        // 3. 자산 계산 (이번에 체결된 수량 'executeVol' 만큼만 계산!)
        BigDecimal executionPrice = order.getOriginalPrice();
        BigDecimal totalExecutionCost = executionPrice.multiply(executeVol);
        String coinSymbol = market.replace("KRW-", ""); 

        if ("BID".equals(expectedSide)) {
            // 원화 금고 차감 -> 코인 지갑 증가
            String deductLockedSql = "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = 'KRW' AND locked >= ?";
            try (PreparedStatement dStmt = conn.prepareStatement(deductLockedSql)) {
                dStmt.setBigDecimal(1, totalExecutionCost); dStmt.setString(2, order.getUserId()); dStmt.setLong(3, order.getSessionId()); dStmt.setBigDecimal(4, totalExecutionCost);
                dStmt.executeUpdate();
            }
            String addCoinSql = "INSERT INTO assets (user_id, session_id, currency, balance, locked) VALUES (?, ?, ?, ?, 0) ON DUPLICATE KEY UPDATE balance = balance + ?";
            try (PreparedStatement aStmt = conn.prepareStatement(addCoinSql)) {
                aStmt.setString(1, order.getUserId()); aStmt.setLong(2, order.getSessionId()); aStmt.setString(3, coinSymbol); aStmt.setBigDecimal(4, executeVol); aStmt.setBigDecimal(5, executeVol);
                aStmt.executeUpdate();
            }
        } else {
            // 코인 금고 차감 -> 원화 지갑 증가
            String deductLockedSql = "UPDATE assets SET locked = locked - ? WHERE user_id = ? AND session_id = ? AND currency = ? AND locked >= ?";
            try (PreparedStatement dStmt = conn.prepareStatement(deductLockedSql)) {
                dStmt.setBigDecimal(1, executeVol); dStmt.setString(2, order.getUserId()); dStmt.setLong(3, order.getSessionId()); dStmt.setString(4, coinSymbol); dStmt.setBigDecimal(5, executeVol);
                dStmt.executeUpdate();
            }
            String addKrwSql = "UPDATE assets SET balance = balance + ? WHERE user_id = ? AND session_id = ? AND currency = 'KRW'";
            try (PreparedStatement aStmt = conn.prepareStatement(addKrwSql)) {
                aStmt.setBigDecimal(1, totalExecutionCost); aStmt.setString(2, order.getUserId()); aStmt.setLong(3, order.getSessionId());
                aStmt.executeUpdate();
            }
        }

        // 4. 영수증(executions) 발급 (부분 체결된 만큼만!)
        String insertExecSql = "INSERT INTO executions (order_id, user_id, market, side, price, volume, total_price, fee) VALUES (?, ?, ?, ?, ?, ?, ?, 0)";
        try (PreparedStatement insertExec = conn.prepareStatement(insertExecSql)) {
            insertExec.setLong(1, order.getOrderId()); insertExec.setString(2, order.getUserId()); insertExec.setString(3, market); insertExec.setString(4, expectedSide);
            insertExec.setBigDecimal(5, executionPrice); insertExec.setBigDecimal(6, executeVol); insertExec.setBigDecimal(7, totalExecutionCost);
            insertExec.executeUpdate();
        }

        // 5. 알림 및 UI 업데이트를 위해 반환할 객체 세팅 (이번에 체결된 정보만 담음)
        OrderDTO partialExecOrder = new OrderDTO();
        partialExecOrder.setOrderId(order.getOrderId());
        partialExecOrder.setSide(expectedSide);
        partialExecOrder.setOriginalPrice(executionPrice);
        partialExecOrder.setOriginalVolume(executeVol); // 💡 팝업창에 "얼마나 체결되었는지" 띄우기 위함
        executedList.add(partialExecOrder);

        // 6. 10개 중 3개를 썼다면, 남은 7개를 다음 사람을 위해 반환!
        return availableTradeVolume.subtract(executeVol);
    }

        // Result Set 매핑 헬퍼 메서드
    private OrderDTO mapResultSetToOrderDTO(ResultSet rs) throws SQLException {
        OrderDTO order = new OrderDTO();
        order.setOrderId(rs.getLong("order_id"));
        order.setUserId(rs.getString("user_id"));
        order.setSessionId(rs.getLong("session_id"));
        order.setMarket(rs.getString("market"));
        order.setSide(rs.getString("side"));
        order.setOriginalPrice(rs.getBigDecimal("original_price"));
        order.setOriginalVolume(rs.getBigDecimal("original_volume"));
        
        // 💡 [핵심 추가] DB에서 '남은 수량'을 꺼내서 DTO에 담아줍니다!
        BigDecimal remainingVol = rs.getBigDecimal("remaining_volume");
        // 만약 예전 데이터라서 DB에 남은 수량이 비어있다면(null), 원래 수량으로 채워주는 방어 코드!
        order.setRemainingVolume(remainingVol != null ? remainingVol : rs.getBigDecimal("original_volume"));
        
        order.setStatus(rs.getString("status"));
        return order;
    }

        //유저의 자산(Balance, Locked) 정보를 가져오는 메서드 (하위 호환용)
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
                System.err.println("자산 정보 로드 실패: " + e.getMessage());
                e.printStackTrace();
            }
        }

     // 유저의 특정 세션 내 미체결 대기 주문(WAIT) 목록을 가져오는 메서드
        public List<OrderDTO> getOpenOrders(String userId, long sessionId) {
            List<OrderDTO> openOrders = new ArrayList<>();
            
            //SQL문에 session_id 조건을 반드시 추가!
            String sql = "SELECT * FROM orders WHERE user_id = ? AND session_id = ? AND status = 'WAIT'";
            
            try (Connection conn = DBConnection.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                
                pstmt.setString(1, userId);
                pstmt.setLong(2, sessionId); //세션 ID 바인딩
                
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        OrderDTO order = new OrderDTO();
                        order.setOrderId(rs.getLong("order_id"));
                        order.setSide(rs.getString("side"));
                        order.setOriginalPrice(rs.getBigDecimal("original_price"));
                        order.setOriginalVolume(rs.getBigDecimal("original_volume"));
                        
                        //DB의 남은 수량을 DTO에 담습니다.
                        order.setRemainingVolume(rs.getBigDecimal("remaining_volume"));
                        
                        order.setStatus(rs.getString("status"));
                        order.setMarket(rs.getString("market")); 
                        
                        openOrders.add(order);
                    }
                }
            } catch (SQLException e) {
                System.err.println(">> [DB 에러] 미체결 주문 로드 실패: " + e.getMessage());
                e.printStackTrace();
            }
            return openOrders;
        }
}