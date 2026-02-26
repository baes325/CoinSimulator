package DAO;

import DTO.AutoOrderDTO;
import com.team.coin_simulator.DBConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class AutoOrderDAO {

    // 1. 새로운 특수 주문 예약하기
    public boolean insertAutoOrder(AutoOrderDTO dto) {
        String sql = "INSERT INTO auto_orders (user_id, session_id, market, trigger_price, volume, condition_type, side) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, dto.getUserId());
            pstmt.setLong(2, dto.getSessionId());
            pstmt.setString(3, dto.getMarket());
            pstmt.setBigDecimal(4, dto.getTriggerPrice());
            pstmt.setBigDecimal(5, dto.getVolume());
            pstmt.setString(6, dto.getConditionType());
            pstmt.setString(7, dto.getSide());
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    // 2. 현재 살아있는(감시 중인) 자동 매매 목록 가져오기 (엔진용)
    public List<AutoOrderDTO> getActiveAutoOrders(String userId, long sessionId) {
        List<AutoOrderDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM auto_orders WHERE user_id = ? AND session_id = ? AND is_active = TRUE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    AutoOrderDTO dto = new AutoOrderDTO();
                    dto.setAutoId(rs.getLong("auto_id"));
                    dto.setUserId(rs.getString("user_id"));
                    dto.setSessionId(rs.getLong("session_id"));
                    dto.setMarket(rs.getString("market"));
                    dto.setTriggerPrice(rs.getBigDecimal("trigger_price"));
                    dto.setVolume(rs.getBigDecimal("volume"));
                    dto.setConditionType(rs.getString("condition_type"));
                    dto.setSide(rs.getString("side"));
                    dto.setActive(rs.getBoolean("is_active"));
                    list.add(dto);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return list;
    }

    // 3. 주문이 체결된 후 사망 처리하기
    public boolean markAsTriggered(long autoId) {
        String sql = "UPDATE auto_orders SET is_active = FALSE WHERE auto_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, autoId);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }
    //자동/예약 주문 취소 및 삭제
    public boolean cancelAutoOrder(long autoId) {
        String sql = "DELETE FROM auto_orders WHERE auto_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, autoId);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) { 
            e.printStackTrace(); 
            return false; 
        }
    }
}