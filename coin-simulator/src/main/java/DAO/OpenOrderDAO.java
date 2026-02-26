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
	
    // ── 세션 필터 적용 메서드 (신규) ──────────────────────────────

    /**
     * 사용자의 미체결 주문 목록 조회 (세션 필터 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @return 미체결 주문 리스트
     */
    public List<OrderDTO> getOpenOrders(String userId, long sessionId) {
        List<OrderDTO> list = new ArrayList<>();

        String sql =
            "SELECT * FROM orders " +
            "WHERE user_id = ? AND session_id = ? AND status = 'WAIT' " +
            "ORDER BY created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDTO(rs));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return list;
    }

    /**
     * 특정 마켓의 미체결 주문 조회 (세션 필터 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @param market    마켓 코드 (예: KRW-BTC)
     * @return 미체결 주문 리스트
     */
    public List<OrderDTO> getOpenOrdersByMarket(String userId, long sessionId, String market) {
        List<OrderDTO> list = new ArrayList<>();

        String sql =
            "SELECT * FROM orders " +
            "WHERE user_id = ? AND session_id = ? AND market = ? AND status = 'WAIT' " +
            "ORDER BY created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setString(3, market);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDTO(rs));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return list;
    }

    // ── 기존 메서드 (하위 호환용 — 세션 구분 없이 전체 조회) ────────

    /** @deprecated sessionId를 명시하는 {@link #getOpenOrders(String, long)} 사용 권장 */
    @Deprecated
    public List<OrderDTO> getOpenOrders(String userId) {
        List<OrderDTO> list = new ArrayList<>();
        String sql =
            "SELECT * FROM orders " +
            "WHERE user_id = ? AND status = 'WAIT' " +
            "ORDER BY created_at DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) list.add(mapResultSetToDTO(rs));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    /** @deprecated sessionId를 명시하는 {@link #getOpenOrdersByMarket(String, long, String)} 사용 권장 */
    @Deprecated
    public List<OrderDTO> getOpenOrdersByMarket(String userId, String market) {
        List<OrderDTO> list = new ArrayList<>();
        String sql =
            "SELECT * FROM orders " +
            "WHERE user_id = ? AND market = ? AND status = 'WAIT' " +
            "ORDER BY created_at DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setString(2, market);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) list.add(mapResultSetToDTO(rs));
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return list;
    }

    // ── 취소 메서드 (세션 무관하게 order_id 기준) ────────────────

    /**
     * 특정 주문 취소
     *
     * @param orderId 주문 ID
     * @return 성공 여부
     */
    public boolean cancelOrder(long orderId) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 💡 동시성 및 에러 방어를 위해 트랜잭션 시작

            // 1. 취소할 주문의 정보 가져오기
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
                        throw new SQLException("취소할 주문을 찾을 수 없거나 이미 처리되었습니다.");
                    }
                }
            }

            // 2. 주문 상태를 CANCEL로 변경
            String cancelSql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND status = 'WAIT'";
            try (PreparedStatement pstmt = conn.prepareStatement(cancelSql)) {
                pstmt.setLong(1, orderId);
                if (pstmt.executeUpdate() == 0) throw new SQLException("주문 취소 업데이트 실패");
            }

            // 3. 잃어버릴 뻔한 내 돈! 환불 금액 정밀 계산 (🚀 소수점 먼지 제거)
            BigDecimal refundAmount;
            String currency;

            if ("BID".equals(side)) {
                // 매수 취소: (남은 수량 * 지정 가격) + 0.05% 수수료
                currency = "KRW";
                BigDecimal cost = price.multiply(remainingVol);
                BigDecimal fee = cost.multiply(FEE_RATE);
                // 💡 [핵심] 소수점 8자리 밑의 미세한 오차는 무조건 버림(DOWN) 처리하여 잔고 초과 에러 방지!
                refundAmount = cost.add(fee).setScale(8, java.math.RoundingMode.DOWN); 
            } else {
                // 매도 취소: 코인 반환
                currency = market.replace("KRW-", "");
                // 💡 코인도 동일하게 8자리 버림 처리
                refundAmount = remainingVol.setScale(8, java.math.RoundingMode.DOWN);
            }

            System.out.println(">> [디버깅] 환불 시도 -> 통화: " + currency + " / 환불액: " + refundAmount.toPlainString());

            // 4. 지갑(assets)에 돈 돌려주기
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
                    throw new SQLException("자산 환불 실패: 잠긴(locked) 잔고가 부족합니다! (환불요청액: " + refundAmount + ")");
                }
            }

            conn.commit(); 
            System.out.println(">> [DB] 주문번호 " + orderId + " 취소 및 환불 완벽 성공!");
            return true;

        } catch (SQLException e) {
            if (conn != null) try { conn.rollback(); } catch (SQLException ex) {} 
            System.err.println(">> [주문 취소 에러] " + e.getMessage()); // 🚀 이 메시지가 콘솔에 찍히면 원인 파악 끝!
            return false;
        } finally {
            if (conn != null) try { conn.close(); } catch (SQLException ex) {}
        }
    }

    /**
     * 사용자 + 세션의 모든 미체결 주문 일괄 취소
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @return 취소된 주문 수
     */
    public int cancelAllOrders(String userId, long sessionId) {
        String sql = "UPDATE orders SET status = 'CANCEL' " +
                     "WHERE user_id = ? AND session_id = ? AND status = 'WAIT'";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            return pstmt.executeUpdate();

        } catch (SQLException e) {
            e.printStackTrace();
            return 0;
        }
    }

    /** @deprecated sessionId를 명시하는 {@link #cancelAllOrders(String, long)} 사용 권장 */
    @Deprecated
    public int cancelAllOrders(String userId) {
        String sql = "UPDATE orders SET status = 'CANCEL' WHERE user_id = ? AND status = 'WAIT'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            return pstmt.executeUpdate();
        } catch (SQLException e) { e.printStackTrace(); return 0; }
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