package DAO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.team.coin_simulator.DBConnection;

import DTO.ExecutionDTO;

/**
 * 투자손익 데이터 조회 DAO
 * executions 테이블에서 데이터를 조회합니다.
 * 모든 public 조회 메서드는 sessionId 를 받아 해당 세션의 데이터만 반환합니다.
 */
public class ProfitLossDAO {

    // ── 세션 필터 적용 메서드 (신규) ──────────────────────────────

    /**
     * 특정 사용자의 최근 N일간 모든 체결 내역 조회 (세션 필터 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @param days      조회할 일수
     * @return 체결 내역 리스트 (최신순)
     */
    public List<ExecutionDTO> getExecutions(String userId, long sessionId, int days) {
        List<ExecutionDTO> resultList = new ArrayList<>();

        String sql =
            "SELECT e.* " +
            "FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? " +
            "  AND o.session_id = ? " +
            "  AND e.executed_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
            "ORDER BY e.executed_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setInt(3, days);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) resultList.add(mapToDTO(rs));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return resultList;
    }

    /**
     * 매도(ASK) 체결만 조회 — 실현 손익이 있는 거래 (세션 필터 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @param days      조회할 일수
     * @return 매도 체결 내역 리스트
     */
    public List<ExecutionDTO> getSellExecutions(String userId, long sessionId, int days) {
        List<ExecutionDTO> resultList = new ArrayList<>();

        String sql =
            "SELECT e.* " +
            "FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? " +
            "  AND o.session_id = ? " +
            "  AND e.side = 'ASK' " +
            "  AND e.executed_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
            "ORDER BY e.executed_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setInt(3, days);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) resultList.add(mapToDTO(rs));
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return resultList;
    }

    /**
     * 일별로 그룹화된 손익 집계 (세션 필터 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @param days      조회할 일수
     * @return 날짜별 손익 맵 (key: 날짜, value: 총 실현손익)
     */
    public Map<Date, BigDecimal> getDailyPnlSummary(String userId, long sessionId, int days) {
        Map<Date, BigDecimal> result = new LinkedHashMap<>();

        String sql =
            "SELECT " +
            "    DATE(e.executed_at) AS trade_date, " +
            "    SUM(CASE WHEN e.side = 'ASK' THEN e.realized_pnl ELSE 0 END) AS daily_pnl " +
            "FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? " +
            "  AND o.session_id = ? " +
            "  AND e.executed_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
            "GROUP BY DATE(e.executed_at) " +
            "ORDER BY trade_date DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setInt(3, days);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    result.put(rs.getDate("trade_date"), rs.getBigDecimal("daily_pnl"));
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return result;
    }

    /**
     * 사용자 + 세션의 총 실현 손익 조회
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @return 총 실현 손익
     */
    public BigDecimal getTotalRealizedPnl(String userId, long sessionId) {
        String sql =
            "SELECT SUM(e.realized_pnl) AS total_pnl " +
            "FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? " +
            "  AND o.session_id = ? " +
            "  AND e.side = 'ASK'";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal totalPnl = rs.getBigDecimal("total_pnl");
                    return totalPnl != null ? totalPnl : BigDecimal.ZERO;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return BigDecimal.ZERO;
    }

    // ── 세션 무관 메서드 ────────────────────────────────────────

    /**
     * 사용자의 초기 자본금 조회 (활성 세션 기준)
     * sessionId를 직접 넘기는 오버로드도 제공합니다.
     *
     * @param userId 사용자 ID
     * @return 초기 자본금 (없으면 기본값 1억)
     */
    public long getInitialSeedMoney(String userId) {
        String sql =
            "SELECT initial_seed_money " +
            "FROM simulation_sessions " +
            "WHERE user_id = ? AND is_active = TRUE " +
            "ORDER BY created_at DESC LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal seedMoney = rs.getBigDecimal("initial_seed_money");
                    return seedMoney != null ? seedMoney.longValue() : 100_000_000L;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 100_000_000L;
    }

    /**
     * 특정 세션의 초기 자본금 조회
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @return 초기 자본금 (없으면 기본값 1억)
     */
    public long getInitialSeedMoney(String userId, long sessionId) {
        String sql =
            "SELECT initial_seed_money " +
            "FROM simulation_sessions " +
            "WHERE user_id = ? AND session_id = ? LIMIT 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal seedMoney = rs.getBigDecimal("initial_seed_money");
                    return seedMoney != null ? seedMoney.longValue() : 100_000_000L;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return 100_000_000L;
    }

    // ── 기존 메서드 (하위 호환용 — @Deprecated) ─────────────────

    /** @deprecated sessionId를 명시하는 {@link #getExecutions(String, long, int)} 사용 권장 */
    @Deprecated
    public List<ExecutionDTO> getExecutions(String userId, int days) {
        List<ExecutionDTO> resultList = new ArrayList<>();
        String sql =
            "SELECT e.* FROM executions e INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? AND e.executed_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
            "ORDER BY e.executed_at DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId); pstmt.setInt(2, days);
            try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) resultList.add(mapToDTO(rs)); }
        } catch (SQLException e) { e.printStackTrace(); }
        return resultList;
    }

    /** @deprecated sessionId를 명시하는 {@link #getSellExecutions(String, long, int)} 사용 권장 */
    @Deprecated
    public List<ExecutionDTO> getSellExecutions(String userId, int days) {
        List<ExecutionDTO> resultList = new ArrayList<>();
        String sql =
            "SELECT e.* FROM executions e INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? AND e.side = 'ASK' " +
            "  AND e.executed_at >= DATE_SUB(CURDATE(), INTERVAL ? DAY) " +
            "ORDER BY e.executed_at DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId); pstmt.setInt(2, days);
            try (ResultSet rs = pstmt.executeQuery()) { while (rs.next()) resultList.add(mapToDTO(rs)); }
        } catch (SQLException e) { e.printStackTrace(); }
        return resultList;
    }

    /** @deprecated sessionId를 명시하는 {@link #getTotalRealizedPnl(String, long)} 사용 권장 */
    @Deprecated
    public BigDecimal getTotalRealizedPnl(String userId) {
        String sql =
            "SELECT SUM(e.realized_pnl) AS total_pnl FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? AND e.side = 'ASK'";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) { BigDecimal v = rs.getBigDecimal("total_pnl"); return v != null ? v : BigDecimal.ZERO; }
            }
        } catch (SQLException e) { e.printStackTrace(); }
        return BigDecimal.ZERO;
    }
    
    /**
     * 사용자 + 세션의 총 수수료 조회 (매수/매도 전체 포함)
     *
     * @param userId    사용자 ID
     * @param sessionId 세션 ID
     * @return 총 발생 수수료
     */
    public BigDecimal getTotalFee(String userId, long sessionId) {
        String sql =
            "SELECT SUM(e.fee) AS total_fee " +
            "FROM executions e " +
            "INNER JOIN orders o ON e.order_id = o.order_id " +
            "WHERE o.user_id = ? " +
            "  AND o.session_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    BigDecimal totalFee = rs.getBigDecimal("total_fee");
                    return totalFee != null ? totalFee : BigDecimal.ZERO;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return BigDecimal.ZERO;
    }

    // ── 공통 매핑 ───────────────────────────────────────────────

    private ExecutionDTO mapToDTO(ResultSet rs) throws SQLException {
        ExecutionDTO dto = new ExecutionDTO();
        dto.setExecutionId(rs.getLong("execution_id"));
        dto.setOrderId(rs.getLong("order_id"));
        dto.setMarket(rs.getString("market"));
        dto.setSide(rs.getString("side"));
        dto.setPrice(rs.getBigDecimal("price"));
        dto.setVolume(rs.getBigDecimal("volume"));
        dto.setFee(rs.getBigDecimal("fee"));
        dto.setBuyAvgPrice(rs.getBigDecimal("buy_avg_price"));
        dto.setRealizedPnl(rs.getBigDecimal("realized_pnl"));
        dto.setRoi(rs.getBigDecimal("roi"));
        dto.setExecutedAt(rs.getTimestamp("executed_at"));
        
        java.sql.Timestamp ts = rs.getTimestamp("executed_at");
        if (ts != null) {
            long correctedTime = ts.getTime() - (9 * 60 * 60 * 1000L); 
            dto.setExecutedAt(new java.sql.Timestamp(correctedTime));
        }
        
        
        return dto;
    }
}