package DAO;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import com.team.coin_simulator.DBConnection;

import DTO.AssetDTO;

public class AssetDAO {

    // 1. 기존 메서드: 코인만 조회 (테이블 리스트용 - KRW 제외)
    public List<AssetDTO> getUserAssets(String userId) {
        List<AssetDTO> list = new ArrayList<>();
        // KRW는 목록에서 제외하고 코인만 조회
        String sql = "SELECT * FROM assets WHERE user_id = ? AND currency != 'KRW' AND (balance > 0 OR locked > 0)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    AssetDTO dto = new AssetDTO();
                    dto.setUserId(rs.getString("user_id"));
                    dto.setCurrency(rs.getString("currency"));
                    dto.setBalance(rs.getBigDecimal("balance"));
                    dto.setLocked(rs.getBigDecimal("locked"));
                    dto.setAvgBuyPrice(rs.getBigDecimal("avg_buy_price"));
                    list.add(dto);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // 2. [추가된 메서드] 모든 자산 조회 (차트 및 요약용 - KRW 포함)
    public List<AssetDTO> getAllAssets(String userId, long sessionId) {
        List<AssetDTO> list = new ArrayList<>();
        // WHERE 절에 session_id = ? 추가
        String sql = "SELECT * FROM assets WHERE user_id = ? AND session_id = ? AND (balance > 0 OR locked > 0)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    AssetDTO dto = new AssetDTO();
                    dto.setUserId(rs.getString("user_id"));
                    dto.setCurrency(rs.getString("currency"));
                    dto.setBalance(rs.getBigDecimal("balance"));
                    dto.setLocked(rs.getBigDecimal("locked"));
                    dto.setAvgBuyPrice(rs.getBigDecimal("avg_buy_price"));
                    list.add(dto);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return list;
    }

    // 3. [신규 추가] 초기 자산 생성 메서드 (회원가입 시 호출)
    /**
     * 신규 사용자의 초기 KRW 자산을 생성합니다.
     * 
     * @param userId 사용자 ID
     * @param initialAmount 초기 투자금액
     * @return 성공 여부
     */
    public static boolean createInitialAsset(String userId, BigDecimal initialAmount) {
        String sql = "INSERT INTO assets (user_id, currency, session_id, balance, locked, avg_buy_price) " +
                     "VALUES (?, 'KRW', 0, ?, 0, 0)";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setBigDecimal(2, initialAmount);
            
            int result = pstmt.executeUpdate();
            return result > 0;
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // 4. [신규 추가] 특정 자산 존재 여부 확인
    /**
     * 사용자의 특정 통화 자산이 이미 존재하는지 확인
     * 
     * @param userId 사용자 ID
     * @param currency 통화 코드 (KRW, BTC 등)
     * @return 존재 여부
     */
    public static boolean assetExists(String userId, String currency) {
        String sql = "SELECT 1 FROM assets WHERE user_id = ? AND currency = ?";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setString(2, currency);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
    
    // 1. 자산 생성 메서드 수정 (sessionId 추가)
    public static boolean createInitialAsset(String userId, long sessionId, BigDecimal initialAmount) {
        // session_id 부분에 ? 파라미터 적용
        String sql = "INSERT INTO assets (user_id, currency, session_id, balance, locked, avg_buy_price) " +
                     "VALUES (?, 'KRW', ?, ?, 0, 0)";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, userId);
            pstmt.setLong(2, sessionId); // 세션 ID 바인딩
            pstmt.setBigDecimal(3, initialAmount);
            
            int result = pstmt.executeUpdate();
            return result > 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}