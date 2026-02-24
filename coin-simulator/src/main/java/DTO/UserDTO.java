package DTO;

import java.io.Serializable;
import java.sql.Timestamp;

import type.AuthProvider;

public class UserDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    // 1. 핵심 회원 정보
    private String userId;          // user_id (PK, 이메일 주소 등)
    private String password;        // password (반드시 암호화된 문자열이어야 함)
    private String nickname;        // nickname
    
    // 2. 인증 제공자 (기본값: EMAIL)
    private AuthProvider authProvider; 

    // 3. 가입일
    private Timestamp createdAt;    // created_at

    // --- 생성자 (Constructors) ---

    public UserDTO() {
    }

    // 회원가입용 간편 생성자
    public UserDTO(String userId, String password, String nickname) {
        this.userId = userId;
        this.password = password;
        this.nickname = nickname;
        this.authProvider = AuthProvider.EMAIL; // 기본값 설정
    }

    // 소셜 로그인용 생성자 (비번 없음)
    public UserDTO(String userId, String nickname, AuthProvider authProvider) {
        this.userId = userId;
        this.nickname = nickname;
        this.authProvider = authProvider;
    }

    // --- Getter & Setter ---
    // (이클립스 Alt + Shift + S -> R 로 생성)

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getNickname() { return nickname; }
    public void setNickname(String nickname) { this.nickname = nickname; }

    public AuthProvider getAuthProvider() { return authProvider; }
    public void setAuthProvider(AuthProvider authProvider) { this.authProvider = authProvider; }
    
    // DB 문자열 -> Enum 변환용 Setter (편의 기능)
    public void setAuthProvider(String providerStr) {
        try {
            this.authProvider = AuthProvider.valueOf(providerStr);
        } catch (Exception e) {
            this.authProvider = AuthProvider.EMAIL; // 예외 발생 시 기본값
        }
    }

    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }

    @Override
    public String toString() {
        // 보안상 비밀번호는 로그에 찍지 않는 것이 원칙입니다.
        return "UserDTO [userId=" + userId + ", nickname=" + nickname + 
               ", provider=" + authProvider + "]"; 
    }
}