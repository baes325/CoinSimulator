package DAO;

import java.sql.*;
import com.team.coin_simulator.DBConnection;

public class SimulationSessionDAO {

	public static long createSession(Connection conn, String userId) throws SQLException {
	    String sql = "INSERT INTO simulation_sessions (user_id, session_name, session_type) " +
	                 "VALUES (?, 'DEFAULT', 'REALTIME')";

	    try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
	        ps.setString(1, userId);
	        ps.executeUpdate();

	        try (ResultSet rs = ps.getGeneratedKeys()) {
	            if (!rs.next()) throw new SQLException("session_id 생성 실패");
	            return rs.getLong(1);
	        }
	    }
	}
	
    public static Long createRealtimeSession(String userId) {

        String sql =
            "INSERT INTO simulation_sessions " +
            "(user_id, session_name, session_type) " +
            "VALUES (?, 'DEFAULT', 'REALTIME')";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps =
               conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {

            ps.setString(1, userId);
            ps.executeUpdate();

            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) {
                    return rs.getLong(1); // ⭐ session_id 반환
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }

        return null;
    }
}