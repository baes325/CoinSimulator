package com.team.coin_simulator.backtest;

import DTO.SessionDTO;

public class SessionManager {
    private static SessionManager instance = new SessionManager();
    
    // 현재 진행 중인 세션 객체
    private SessionDTO currentSession; 
    
    private SessionManager() {}
    
    public static SessionManager getInstance() {
        return instance;
    }
    
    public void setCurrentSession(SessionDTO session) {
        this.currentSession = session;
    }
    
    public SessionDTO getCurrentSession() {
        return currentSession;
    }
    
    // 현재 활성화된 세션의 실제 ID를 반환합니다.
    public long getCurrentSessionId() {
        if (currentSession == null || currentSession.getSessionId() == null) {
            System.err.println("[SessionManager] 경고: 현재 설정된 세션이 없습니다!");
            return -1L; // 세션이 없을 경우 에러 처리를 위해 -1 반환
        }
        return currentSession.getSessionId();
    }
}