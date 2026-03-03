package DAO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.team.coin_simulator.DBConnection;
import DTO.OrderDTO;

/**
 * 미체결 주문 조회 및 관리 DAO
 */
public class OpenOrderDAO {

    private static final BigDecimal FEE_RATE = new BigDecimal("0.0005");
    
    // ── 세션 필터 적용 메서드 ──────────────────────────────

    public List<OrderDTO> getOpenOrders(String userId, long sessionId) {
        List<OrderDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE user_id = ? AND session_id = ? AND status = 'WAIT' ORDER BY created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) list.add(mapResultSetToDTO(rs));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    public List<OrderDTO> getOpenOrdersByMarket(String userId, long sessionId, String market) {
        List<OrderDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM orders WHERE user_id = ? AND session_id = ? AND market = ? AND status = 'WAIT' ORDER BY created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setString(3, market);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) list.add(mapResultSetToDTO(rs));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }


    // ── 취소 메서드 (개별 / 일괄 환불 완벽 처리) ────────────────

    /**
     * 특정 주문 1개 취소 및 환불
     */
    public boolean cancelOrder(long orderId) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); 

            // 1. 주문 정보 및 환불액 계산 헬퍼 호출
            if (!processRefundForOrder(conn, orderId)) {
                throw new SQLException("환불 처리 실패 (주문을 찾을 수 없거나 잔고 오류)");
            }

            // 2. 주문 상태 변경
            String cancelSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND status = 'WAIT'";
            try (PreparedStatement pstmt = conn.prepareStatement(cancelSql)) {
                pstmt.setLong(1, orderId);
                if (pstmt.executeUpdate() == 0) throw new SQLException("주문 취소 업데이트 실패");
            }

            conn.commit(); 
            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {} 
            System.err.println(">> [주문 개별 취소 에러] " + e.getMessage());
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ex) {}
        }
    }

    /**
     * 특정 세션의 모든 미체결 주문 일괄 취소 및 환불
     * @return 취소된 주문 수
     */
    public int cancelAllOrders(String userId, long sessionId) {
        Connection conn = null;
        int canceledCount = 0;
        
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false);

            // 1. 취소해야 할 모든 대기 주문 ID를 가져옴
            String fetchSql = "SELECT order_id FROM orders WHERE user_id = ? AND session_id = ? AND status = 'WAIT'";
            List<Long> orderIdsToCancel = new ArrayList<>();
            
            try (PreparedStatement pstmt = conn.prepareStatement(fetchSql)) {
                pstmt.setString(1, userId);
                pstmt.setLong(2, sessionId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        orderIdsToCancel.add(rs.getLong("order_id"));
                    }
                }
            }

            // 2. 각 주문마다 환불 로직 실행
            for (Long orderId : orderIdsToCancel) {
                if (processRefundForOrder(conn, orderId)) {
                    String cancelSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ?";
                    try (PreparedStatement pstmt = conn.prepareStatement(cancelSql)) {
                        pstmt.setLong(1, orderId);
                        pstmt.executeUpdate();
                        canceledCount++;
                    }
                }
            }

            conn.commit();
            return canceledCount;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {}
            System.err.println(">> [주문 일괄 취소 에러] " + e.getMessage());
            return 0;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ex) {}
        }
    }

    // ── 내부 환불 전용 도우미 메서드 ──────────────────────────────

    /**
     * 단일 주문에 대해 금액을 계산하고 DB 자산(assets)을 환불하는 핵심 로직
     */
    private boolean processRefundForOrder(Connection conn, long orderId) throws SQLException {
        String fetchSql = "SELECT user_id, session_id, market, side, original_price, remaining_volume " +
                          "FROM orders WHERE order_id = ? AND status = 'WAIT'";
        
        String userId = null;
        long sessionId = 0;
        String market = null;
        String side = null;
        BigDecimal price = BigDecimal.ZERO;
        BigDecimal remainingVol = BigDecimal.ZERO;

        try (PreparedStatement pstmt = conn.prepareStatement(fetchSql)) {
            pstmt.setLong(1, orderId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    userId = rs.getString("user_id");
                    sessionId = rs.getLong("session_id");
                    market = rs.getString("market");
                    side = rs.getString("side");
                    price = rs.getBigDecimal("original_price");
                    remainingVol = rs.getBigDecimal("remaining_volume");
                } else {
                    return false; // 이미 체결됐거나 없는 주문
                }
            }
        }

        BigDecimal refundAmount;
        String currency;

        if ("BID".equals(side)) {
            // 매수 취소: 원금 + 수수료 돌려주기
            currency = "KRW";
            BigDecimal cost = price.multiply(remainingVol);
            BigDecimal fee = cost.multiply(FEE_RATE);
            refundAmount = cost.add(fee).setScale(8, java.math.RoundingMode.DOWN); 
        } else {
            // 매도 취소: 코인 수량 돌려주기
            currency = market.replace("KRW-", "");
            refundAmount = remainingVol.setScale(8, java.math.RoundingMode.DOWN);
        }

        String refundSql = "UPDATE assets SET balance = balance + ?, locked = locked - ? " +
                           "WHERE user_id = ? AND session_id = ? AND currency = ? AND locked >= ?";
        
        try (PreparedStatement pstmt = conn.prepareStatement(refundSql)) {
            pstmt.setBigDecimal(1, refundAmount);
            pstmt.setBigDecimal(2, refundAmount);
            pstmt.setString(3, userId);
            pstmt.setLong(4, sessionId);
            pstmt.setString(5, currency);
            pstmt.setBigDecimal(6, refundAmount); 

            if (pstmt.executeUpdate() == 0) {
                System.err.println(">> [경고] 환불 금액 부족! (요청액: " + refundAmount + " / 코인: " + currency + ")");
                return false; 
            }
        }
        return true;
    }

    // ── 공통 매핑 ───────────────────────────────────────────────

    private OrderDTO mapResultSetToDTO(ResultSet rs) throws SQLException {
        OrderDTO dto = new OrderDTO();
        dto.setOrderId(rs.getLong("order_id"));
        dto.setUserId(rs.getString("user_id"));
        dto.setSessionId(rs.getLong("session_id"));
        dto.setMarket(rs.getString("market"));
        dto.setSide(rs.getString("side"));
        dto.setType(rs.getString("type"));
        dto.setOriginalPrice(rs.getBigDecimal("original_price"));
        dto.setOriginalVolume(rs.getBigDecimal("original_volume"));
        dto.setRemainingVolume(rs.getBigDecimal("remaining_volume"));
        dto.setStatus(rs.getString("status"));
        dto.setCreatedAt(rs.getTimestamp("created_at"));
        return dto;
    }
}