package DAO;

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
        String sql = "UPDATE orders SET status = 'CANCEL' WHERE order_id = ? AND status = 'WAIT'";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setLong(1, orderId);
            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
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