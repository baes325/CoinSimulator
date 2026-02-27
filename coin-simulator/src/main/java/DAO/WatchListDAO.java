package DAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import com.team.coin_simulator.DBConnection; // DB 연결 클래스가 있다고 가정

import DTO.WatchlistDTO;

public class WatchListDAO {

    public List<WatchlistDTO> getWatchlistByUser(String userId) {
        List<WatchlistDTO> list = new ArrayList<>();
        String sql = "SELECT user_id, market, created_at FROM watchlists WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    WatchlistDTO dto = new WatchlistDTO();
                    dto.setUser(rs.getString("user_id"));
                    dto.setMarket(rs.getString("market"));
                    dto.setCreate_at(rs.getTimestamp("created_at"));
                    list.add(dto);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }
    
    
    public boolean isWatchlist(String userId, String market) {
        String sql = "SELECT 1 FROM watchlists WHERE user_id = ? AND market = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setString(2, market);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    // 관심 코인 추가
    public void addWatchlist(String userId, String market) {
        String sql = "INSERT INTO watchlists (user_id, market) VALUES (?, ?)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setString(2, market);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 관심 코인 삭제
    public void removeWatchlist(String userId, String market) {
        String sql = "DELETE FROM watchlists WHERE user_id = ? AND market = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, userId);
            pstmt.setString(2, market);
            pstmt.executeUpdate();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}