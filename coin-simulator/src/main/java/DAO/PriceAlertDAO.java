package DAO;

import com.team.coin_simulator.DBConnection;

import DTO.PriceAlertDTO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class PriceAlertDAO {

    // 알림 조건 추가하기 (DB 저장)
    public boolean addPriceAlert(String userId, String market, BigDecimal targetPrice, String condition) {
    	String cleanMarket = market.replace("KRW-", "");
    	String sql = "INSERT INTO price_alerts (user_id, market, target_price, condition_type, is_active) VALUES (?, ?, ?, ?, TRUE)";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setString(2, market);
            pstmt.setBigDecimal(3, targetPrice);
            pstmt.setString(4, condition); // 'ABOVE' or 'BELOW'
            
            int result = pstmt.executeUpdate();
            return result > 0; // 성공하면 true 반환
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
 //켜질 때 DB에서 살아있는 알림만 메모리로 싹 가져오기
    public List<PriceAlertDTO> getActiveAlerts(String userId) {
        List<PriceAlertDTO> alerts = new ArrayList<>();
        String sql = "SELECT * FROM price_alerts WHERE user_id = ? AND is_active = TRUE";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    PriceAlertDTO alert = new PriceAlertDTO();
                    alert.setAlertId(rs.getLong("alert_id"));
                    alert.setUserId(rs.getString("user_id"));
                    alert.setMarket(rs.getString("market"));
                    alert.setTargetPrice(rs.getBigDecimal("target_price"));
                    alert.setConditionType(rs.getString("condition_type"));
                    alert.setActive(rs.getBoolean("is_active"));
                    alerts.add(alert);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return alerts;
    }

    //알림 발송 후 FALSE로 변경
    public void markAsTriggered(long alertId) {
        String sql = "UPDATE price_alerts SET is_active = FALSE WHERE alert_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, alertId);
            pstmt.executeUpdate();
        } catch (Exception e) { e.printStackTrace(); }
    }
    
 // 알림 목록 가져오기 (종료된 알림 포함)
    public List<PriceAlertDTO> getAllAlertsForUser(String userId) {
        List<PriceAlertDTO> alerts = new ArrayList<>();
        // 최신 알림이 위로 오도록 내림차순 정렬(DESC)
        String sql = "SELECT * FROM price_alerts WHERE user_id = ? ORDER BY alert_id DESC";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    PriceAlertDTO alert = new PriceAlertDTO();
                    alert.setAlertId(rs.getLong("alert_id"));
                    alert.setUserId(rs.getString("user_id"));
                    alert.setMarket(rs.getString("market"));
                    alert.setTargetPrice(rs.getBigDecimal("target_price"));
                    alert.setConditionType(rs.getString("condition_type"));
                    alert.setActive(rs.getBoolean("is_active"));
                    alerts.add(alert);
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return alerts;
    }

    // 꺼진 알림 다시 켜기 (재활성화)
    public boolean reactivateAlert(long alertId) {
        String sql = "UPDATE price_alerts SET is_active = TRUE WHERE alert_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, alertId);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }

    // 알림 영구 삭제
    public boolean deleteAlert(long alertId) {
        String sql = "DELETE FROM price_alerts WHERE alert_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setLong(1, alertId);
            return pstmt.executeUpdate() > 0;
        } catch (Exception e) { e.printStackTrace(); return false; }
    }
}