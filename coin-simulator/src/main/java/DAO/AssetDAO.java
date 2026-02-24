package DAO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import com.team.coin_simulator.DBConnection;
import DTO.AssetDTO;

public class AssetDAO {

    /**
     * 보유 코인 리스트 조회 (KRW 제외)
     * 반드시 현재 sessionId 조건이 포함되어야 합니다.
     */
    public List<AssetDTO> getUserAssets(String userId, long sessionId) {
        List<AssetDTO> list = new ArrayList<>();
        // WHERE 절에 session_id = ? 추가 완료
        String sql = "SELECT * FROM assets WHERE user_id = ? AND session_id = ? AND currency != 'KRW' AND (balance != 0 OR locked != 0)";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDTO(rs));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 모든 자산 조회 (요약 패널 및 차트용)
     */
    public List<AssetDTO> getAllAssets(String userId, long sessionId) {
        List<AssetDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM assets WHERE user_id = ? AND session_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapResultSetToDTO(rs));
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 초기 자산 생성 (실시간용 0번 또는 백테스팅용 세션번호)
     */
    public static boolean createInitialKRW(String userId, Long sessionId) {

        String sql =
            "INSERT INTO assets (session_id, user_id, currency, balance) " +
            "VALUES (?, ?, 'KRW', 100000000)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setLong(1, sessionId);
            ps.setString(2, userId);

            return ps.executeUpdate() == 1;

        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }
    public static boolean createInitialAsset(String userId, long sessionId, BigDecimal initialAmount) {
        String sql = "INSERT INTO assets (user_id, currency, session_id, balance, locked, avg_buy_price) " +
                     "VALUES (?, 'KRW', ?, ?, 0, 0) " +
                     "ON DUPLICATE KEY UPDATE balance = ?"; // 이미 있으면 초기금액으로 리셋
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setBigDecimal(3, initialAmount);
            pstmt.setBigDecimal(4, initialAmount);
            
            int result = pstmt.executeUpdate();
            return result > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 특정 자산 존재 여부 확인 (세션별 격리 확인)
     */
    public static boolean assetExists(String userId, long sessionId, String currency) {
        String sql = "SELECT 1 FROM assets WHERE user_id = ? AND session_id = ? AND currency = ?";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            pstmt.setString(3, currency);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * DTO 매핑 헬퍼 메서드 (코드 중복 제거)
     */
    private AssetDTO mapResultSetToDTO(ResultSet rs) throws java.sql.SQLException {
        AssetDTO dto = new AssetDTO();
        dto.setUserId(rs.getString("user_id"));
        dto.setCurrency(rs.getString("currency"));
        dto.setBalance(rs.getBigDecimal("balance"));
        dto.setLocked(rs.getBigDecimal("locked"));
        dto.setAvgBuyPrice(rs.getBigDecimal("avg_buy_price"));
        return dto;
    }
}