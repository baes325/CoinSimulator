package DAO;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import com.team.coin_simulator.DBConnection;
import DTO.UserDTO;

public class UserDAO {

    // =========================
    // 1) 아이디(이메일) 중복 체크
    // =========================
    public static boolean isIdDuplicate(String userId) {
        String sql = "SELECT 1 FROM users WHERE user_id = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================
    // 2) 회원가입 (users 테이블)
    // =========================
    public static boolean insertUser(UserDTO user, String phone) {
        String cleanPhone = (phone == null) ? null : phone.replaceAll("[^0-9]", "");

        String sql = "INSERT INTO users (user_id, password, nickname, phone_number, auth_provider, created_at) "
                   + "VALUES (?, ?, ?, ?, ?, NOW())";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, user.getUserId());
            pstmt.setString(2, user.getPassword());
            pstmt.setString(3, user.getNickname());
            pstmt.setString(4, cleanPhone);
            pstmt.setString(5, "EMAIL");

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================
    // 3) 휴대폰 번호로 아이디 찾기
    // =========================
    public static String findIdByPhone(String phone) {
        String cleanPhone = (phone == null) ? "" : phone.replaceAll("[^0-9]", "");
        String sql = "SELECT user_id FROM users WHERE phone_number = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, cleanPhone);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getString("user_id");
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // =========================
    // 4) 로그인 체크
    // =========================
    public static UserDTO loginCheck(String userId, String password) {
        // must_change_password 컬럼이 있으면 같이 가져오고 싶지만,
        // 컬럼이 아직 없을 수 있으니 기본 쿼리는 안전하게 유지
        String sql = "SELECT user_id, nickname, profile_image_path FROM users WHERE user_id = ? AND password = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setString(2, password);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    UserDTO user = new UserDTO();
                    user.setUserId(rs.getString("user_id"));
                    user.setNickname(rs.getString("nickname"));
                    user.setProfileImagePath(rs.getString("profile_image_path"));
                    return user;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // =========================
    // 5) userId로 유저 조회
    // =========================
    public static UserDTO getUserById(String userId) {
        String sql = "SELECT user_id, nickname, profile_image_path FROM users WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, userId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    UserDTO u = new UserDTO();
                    u.setUserId(rs.getString("user_id"));
                    u.setNickname(rs.getString("nickname"));
                    u.setProfileImagePath(rs.getString("profile_image_path"));
                    return u;
                }
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    // =========================
    // 6) 프로필 이미지 경로 업데이트
    // =========================
    public static boolean updateProfileImagePath(String userId, String path) {
        String sql = "UPDATE users SET profile_image_path = ? WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, path);
            ps.setString(2, userId);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================
    // 7) 닉네임 업데이트
    // =========================
    public static boolean updateNickname(String userId, String nickname) {
        String sql = "UPDATE users SET nickname = ? WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, nickname);
            ps.setString(2, userId);

            return ps.executeUpdate() > 0;

        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================
    // 8) 비밀번호 업데이트
    //  - 비번 변경 완료 시 must_change_password = 0 (컬럼 있으면)
    // =========================
    public static boolean updatePassword(String userId, String newPassword) {
        // 컬럼이 있으면 플래그도 0으로 내리기
        String sql = "UPDATE users SET password = ?, must_change_password = 0 WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPassword);
            pstmt.setString(2, userId);

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            // 컬럼이 아직 없으면 기존 방식으로라도 업데이트되게 fallback
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unknown column")) {
                String fallback = "UPDATE users SET password = ? WHERE user_id = ?";
                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(fallback)) {

                    pstmt.setString(1, newPassword);
                    pstmt.setString(2, userId);
                    return pstmt.executeUpdate() > 0;

                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            } else {
                e.printStackTrace();
            }
        }
        return false;
    }

    // =========================================================
    // ✅ [추가 1] 이메일(아이디) + 휴대폰 번호로 본인 검증
    // =========================================================
    public static boolean verifyUserByEmailAndPhone(String userId, String phone) {
        String cleanPhone = (phone == null) ? "" : phone.replaceAll("[^0-9]", "");
        String sql = "SELECT 1 FROM users WHERE user_id = ? AND phone_number = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            pstmt.setString(2, cleanPhone);

            try (ResultSet rs = pstmt.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }

    // =========================================================
    // ✅ [추가 2] 임시 비밀번호 발급: password 업데이트 + must_change_password=1
    // =========================================================
    public static boolean issueTemporaryPassword(String userId, String tempPassword) {
        String sql = "UPDATE users SET password = ?, must_change_password = 1 WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, tempPassword);
            pstmt.setString(2, userId);

            return pstmt.executeUpdate() > 0;

        } catch (SQLException e) {
            // 컬럼이 아직 없으면 password만 업데이트 fallback
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unknown column")) {
                String fallback = "UPDATE users SET password = ? WHERE user_id = ?";
                try (Connection conn = DBConnection.getConnection();
                     PreparedStatement pstmt = conn.prepareStatement(fallback)) {

                    pstmt.setString(1, tempPassword);
                    pstmt.setString(2, userId);
                    return pstmt.executeUpdate() > 0;

                } catch (SQLException ex) {
                    ex.printStackTrace();
                }
            } else {
                e.printStackTrace();
            }
        }
        return false;
    }

    // =========================================================
    // ✅ [추가 3] 비밀번호 변경 강제 여부 조회
    // =========================================================
    public static boolean mustChangePassword(String userId) {
        String sql = "SELECT must_change_password FROM users WHERE user_id = ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("must_change_password") == 1;
                }
            }
        } catch (SQLException e) {
            // 컬럼 없으면 강제변경 개념이 없다고 보고 false 처리
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unknown column")) {
                return false;
            }
            e.printStackTrace();
        }
        return false;
    }
}