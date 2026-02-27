package com.team.coin_simulator.backtest;

import com.team.coin_simulator.DBConnection;
import DTO.SessionDTO;
import type.SessionType;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 백테스팅 세션 전용 DAO
 *
 * ■ 세션 생성 규칙
 *   - 시작 시점: 최소 1개월 전
 *   - 기간:      정확히 1개월 (start → start + 1month)
 *   - 중복 방지: 동일 user_id 의 기존 세션과 날짜 겹침 금지
 *
 * ■ 겹침 판정 공식 (SQL)
 *   NOT (end_sim_time <= newStart OR start_sim_time >= newEnd)
 */
public class BacktestSessionDAO {

    private static final int SESSION_DURATION_MONTHS = 1;

    // ──────────────────────────────────────────────
    //  세션 목록 조회
    // ──────────────────────────────────────────────

    /**
     * 사용자의 백테스팅 세션 전체 조회 (최신순)
     */
    public List<SessionDTO> getBacktestSessions(String userId) {
        List<SessionDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM simulation_sessions " +
                     "WHERE user_id = ? AND session_type = 'BACKTEST' " +
                     "ORDER BY created_at DESC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) list.add(map(rs));
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ──────────────────────────────────────────────
    //  중복 검사
    // ──────────────────────────────────────────────

    /**
     * 주어진 시작 시간으로 1개월 세션을 만들 때 기존 세션과 겹치는지 확인합니다.
     *
     * @param userId    사용자 ID
     * @param newStart  새 세션 시작 시각
     * @return true = 겹침 있음 → 생성 불가
     */
    public boolean hasOverlap(String userId, LocalDateTime newStart) {
        LocalDateTime newEnd = newStart.plusMonths(SESSION_DURATION_MONTHS);

        String sql = "SELECT COUNT(*) FROM simulation_sessions " +
                     "WHERE user_id = ? AND session_type = 'BACKTEST' " +
                     "AND NOT (end_sim_time <= ? OR start_sim_time >= ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setTimestamp(2, Timestamp.valueOf(newStart));
            ps.setTimestamp(3, Timestamp.valueOf(newEnd));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // ──────────────────────────────────────────────
    //  세션 생성
    // ──────────────────────────────────────────────

    /**
     * 새 백테스팅 세션을 생성합니다.
     * 겹침 검사는 호출자(Dialog)에서 먼저 수행해야 합니다.
     *
     * @param userId       사용자 ID
     * @param sessionName  세션 이름
     * @param startTime    시뮬레이션 시작 시각 (최소 1개월 전)
     * @param seedMoney    초기 자본금
     * @return 생성된 세션 ID (실패 시 null)
     */
    public Long createSession(String userId, String sessionName,
                              LocalDateTime startTime, long seedMoney) {
        LocalDateTime endTime = startTime.plusMonths(SESSION_DURATION_MONTHS);

        String sql = "INSERT INTO simulation_sessions " +
                     "(user_id, session_name, session_type, initial_seed_money, " +
                     "start_sim_time, current_sim_time, end_sim_time, is_active) " +
                     "VALUES (?, ?, 'BACKTEST', ?, ?, ?, ?, TRUE)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, userId);
            ps.setString(2, sessionName);
            ps.setLong(3, seedMoney);
            ps.setTimestamp(4, Timestamp.valueOf(startTime));
            ps.setTimestamp(5, Timestamp.valueOf(startTime)); // current = start 초기값
            ps.setTimestamp(6, Timestamp.valueOf(endTime));
            ps.executeUpdate();

            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // ──────────────────────────────────────────────
    //  세션 시간 업데이트 (tick마다 호출)
    // ──────────────────────────────────────────────

    public void updateCurrentSimTime(long sessionId, LocalDateTime currentTime) {
        String sql = "UPDATE simulation_sessions SET current_sim_time = ? WHERE session_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.valueOf(currentTime));
            ps.setLong(2, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ──────────────────────────────────────────────
    //  세션 종료 처리
    // ──────────────────────────────────────────────

    public void deactivateSession(long sessionId) {
        String sql = "UPDATE simulation_sessions SET is_active = FALSE WHERE session_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

 // ──────────────────────────────────────────────
    //  DB에 백테스팅 데이터가 존재하는 최소/최대 날짜 조회
    // ──────────────────────────────────────────────

    /**
     * DB의 market_candle 에서 1분봉(unit=1) 기준 가장 오래된 데이터 시각을 반환합니다.
     * (세션 시작 가능 하한선 계산용)
     */
    public LocalDateTime getEarliestCandleTime() {
        String sql = "SELECT MIN(candle_date_time_kst) FROM market_candle WHERE unit = 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (rs.next() && rs.getTimestamp(1) != null) {
                return rs.getTimestamp(1).toLocalDateTime();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null; // 1분봉 데이터가 아예 없는 경우 null 반환
    }

    // ──────────────────────────────────────────────
    //  보유 포지션 존재 여부 확인
    // ──────────────────────────────────────────────

    /**
     * 해당 세션에 미체결 포지션(코인 보유)이 있는지 확인합니다.
     */
    public boolean hasOpenPositions(String userId) {
        String sql = "SELECT COUNT(*) FROM assets " +
                     "WHERE user_id = ? AND currency != 'KRW' AND (balance > 0 OR locked > 0)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // ──────────────────────────────────────────────
    //  ResultSet → SessionDTO 매핑
    // ──────────────────────────────────────────────

    
    private SessionDTO map(ResultSet rs) throws SQLException {
        SessionDTO dto = new SessionDTO();
        dto.setSessionId(rs.getLong("session_id"));
        dto.setUserId(rs.getString("user_id"));
        dto.setSessionName(rs.getString("session_name"));
        dto.setSessionType(SessionType.BACKTEST);
        dto.setInitialSeedMoney(rs.getBigDecimal("initial_seed_money"));
        dto.setStartSimTime(rs.getTimestamp("start_sim_time"));
        dto.setCurrentSimTime(rs.getTimestamp("current_sim_time"));
        dto.setActive(rs.getBoolean("is_active"));
        dto.setCreatedAt(rs.getTimestamp("created_at"));
        return dto;
    }
    
 // ──────────────────────────────────────────────
    //  실시간(REALTIME) 세션 조회 및 생성
    // ──────────────────────────────────────────────

    /**
     * 사용자의 실시간(REALTIME) 세션을 가져옵니다.
     * 만약 데이터베이스에 없다면 자동으로 새로 하나 생성해서 반환합니다.
     */
    public SessionDTO getOrCreateRealtimeSession(String userId) {
        // 1. 기존에 만들어둔 실시간 세션이 있는지 조회
        String selectSql = "SELECT * FROM simulation_sessions " +
                           "WHERE user_id = ? AND session_type = 'REALTIME' LIMIT 1";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(selectSql)) {
            ps.setString(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return map(rs); // 이미 있으면 그대로 반환
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // 2. 없다면 새로 생성 (초기 자본금은 예시로 100,000,000원 설정. 필요시 수정)
        String insertSql = "INSERT INTO simulation_sessions " +
                           "(user_id, session_name, session_type, initial_seed_money, is_active) " +
                           "VALUES (?, '실시간 모의투자', 'REALTIME', 100000000, TRUE)";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, userId);
            ps.executeUpdate();
            
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long newSessionId = keys.getLong(1);
                    SessionDTO dto = new SessionDTO(userId, "실시간 모의투자", type.SessionType.REALTIME, java.math.BigDecimal.valueOf(100000000));
                    dto.setSessionId(newSessionId);
                    return dto;
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }
    
    // ──────────────────────────────────────────────
    //  세션 삭제 (DB 스키마 맞춤형)
    // ──────────────────────────────────────────────
    public boolean deleteSession(long sessionId) {
        Connection conn = null;
        try {
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 트랜잭션 시작

            // 1. 해당 세션의 orders에 연결된 executions(체결 내역) 먼저 삭제
            String deleteExecutionsSql = 
                "DELETE e FROM executions e " +
                "INNER JOIN orders o ON e.order_id = o.order_id " +
                "WHERE o.session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteExecutionsSql)) {
                ps.setLong(1, sessionId);
                ps.executeUpdate();
            }

            // 2. orders(주문 내역) 삭제
            String deleteOrdersSql = "DELETE FROM orders WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteOrdersSql)) {
                ps.setLong(1, sessionId);
                ps.executeUpdate();
            }

            // 3. assets(자산 내역) 삭제 
            // (DB에 ON DELETE CASCADE가 있지만 명시적 삭제로 안정성 확보)
            String deleteAssetsSql = "DELETE FROM assets WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteAssetsSql)) {
                ps.setLong(1, sessionId);
                ps.executeUpdate();
            }

            // 4. 최종적으로 simulation_sessions(세션 본체) 삭제
            String deleteSessionSql = "DELETE FROM simulation_sessions WHERE session_id = ?";
            try (PreparedStatement ps = conn.prepareStatement(deleteSessionSql)) {
                ps.setLong(1, sessionId);
                ps.executeUpdate();
            }

            conn.commit(); // 모든 삭제가 성공하면 DB 반영
            return true;

        } catch (SQLException e) {
            e.printStackTrace();
            if (conn != null) {
                try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
            }
            return false;
        } finally {
            if (conn != null) {
                try { 
                    conn.setAutoCommit(true);
                    conn.close(); 
                } catch (SQLException e) { e.printStackTrace(); }
            }
        }
    }
    
    
    
}