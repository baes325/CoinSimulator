package DAO;

import java.sql.*;
import com.team.coin_simulator.DBConnection;
import DTO.UserDTO;

/**
 * 회원가입 전체 플로우를 관리하는 서비스 클래스
 * 
 * 1. users 테이블에 사용자 생성
 * 2. simulation_sessions 테이블에 기본 세션 생성
 * 3. assets 테이블에 초기 KRW 자산 생성 (세션 ID 포함)
 */
public class SignUpService {

    /**
     * 회원가입 전체 프로세스 실행
     * (트랜잭션으로 묶어서 전부 성공하거나 전부 실패)
     * 
     * @param user 사용자 정보 (userId, password, nickname)
     * @param phone 전화번호 (숫자만)
     * @param initialKRW 초기 투자금액 (KRW)
     * @return 성공 여부
     */
    public static boolean register(UserDTO user, String phone, long initialKRW) {
        
        Connection conn = null;
        
        try {
            // 1. DB 연결 및 트랜잭션 시작
            conn = DBConnection.getConnection();
            conn.setAutoCommit(false); // 트랜잭션 시작
            
            System.out.println("[SignUpService] 회원가입 프로세스 시작: " + user.getUserId());
            
            // 2. users 테이블에 사용자 생성
            boolean userCreated = insertUser(conn, user, phone);
            if (!userCreated) {
                throw new SQLException("사용자 생성 실패");
            }
            System.out.println("[SignUpService] ✅ 사용자 생성 완료");
            
            // 3. simulation_sessions 테이블에 기본 세션 생성
            long sessionId = SimulationSessionDAO.createSession(conn, user.getUserId());
            System.out.println("[SignUpService] ✅ 시뮬레이션 세션 생성 완료 (session_id: " + sessionId + ")");
            
            // 4. assets 테이블에 초기 KRW 자산 생성 (세션 ID 사용)
            boolean assetCreated = createInitialAsset(conn, user.getUserId(), sessionId, initialKRW);
            if (!assetCreated) {
                throw new SQLException("초기 자산 생성 실패");
            }
            System.out.println("[SignUpService] ✅ 초기 자산 생성 완료 (KRW: " + String.format("%,d", initialKRW) + "원)");
            
            // 5. 모든 작업 성공 → 커밋
            conn.commit();
            System.out.println("[SignUpService] ✅✅✅ 회원가입 완료!");
            
            return true;
            
        } catch (Exception e) {
            // 6. 실패 시 롤백
            System.err.println("[SignUpService] ❌ 회원가입 실패: " + e.getMessage());
            e.printStackTrace();
            
            if (conn != null) {
                try {
                    conn.rollback();
                    System.err.println("[SignUpService] 롤백 완료 (모든 변경사항 취소)");
                } catch (SQLException rollbackEx) {
                    System.err.println("[SignUpService] 롤백 실패: " + rollbackEx.getMessage());
                }
            }
            
            return false;
            
        } finally {
            // 7. 연결 정리
            if (conn != null) {
                try {
                    conn.setAutoCommit(true); // 원래 상태로 복구
                    conn.close();
                } catch (SQLException closeEx) {
                    System.err.println("[SignUpService] 연결 종료 실패: " + closeEx.getMessage());
                }
            }
        }
    }

    /**
     * users 테이블에 사용자 삽입
     * 
     * @param conn 트랜잭션용 Connection
     * @param user 사용자 정보
     * @param phone 전화번호
     * @return 성공 여부
     * @throws SQLException
     */
    private static boolean insertUser(Connection conn, UserDTO user, String phone) throws SQLException {
        
        String sql = 
            "INSERT INTO users (user_id, password, nickname, phone_number, auth_provider, profile_image_path, created_at) " +
            "VALUES (?, ?, ?, ?, 'EMAIL', 'default', NOW())";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setString(1, user.getUserId());
            ps.setString(2, user.getPassword());
            ps.setString(3, user.getNickname());
            ps.setString(4, phone);
            
            int result = ps.executeUpdate();
            
            if (result != 1) {
                throw new SQLException("INSERT 실패: 영향받은 행이 " + result + "개");
            }
            
            return true;
        }
    }

    /**
     * assets 테이블에 초기 KRW 자산 생성
     * 
     * @param conn 트랜잭션용 Connection
     * @param userId 사용자 ID
     * @param sessionId 시뮬레이션 세션 ID
     * @param initialKRW 초기 투자금액
     * @return 성공 여부
     * @throws SQLException
     */
    private static boolean createInitialAsset(Connection conn, String userId, long sessionId, long initialKRW) throws SQLException {
        
        String sql = 
            "INSERT INTO assets (session_id, user_id, currency, balance, locked, avg_buy_price) " +
            "VALUES (?, ?, 'KRW', ?, 0, 0)";
        
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setLong(1, sessionId);
            ps.setString(2, userId);
            ps.setLong(3, initialKRW);
            
            int result = ps.executeUpdate();
            
            if (result != 1) {
                throw new SQLException("초기 자산 INSERT 실패: 영향받은 행이 " + result + "개");
            }
            
            return true;
        }
    }
}