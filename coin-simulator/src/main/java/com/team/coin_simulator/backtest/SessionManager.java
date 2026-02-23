package com.team.coin_simulator.backtest;

import DTO.SessionDTO;

public class SessionManager {
    private static SessionManager instance = new SessionManager();
    
    // 현재 진행 중인 세션 객체를 통째로 들고 있거나 ID만 들고 있어도 됩니다.
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
    
    // 편의 메서드: 현재 활성화된 세션의 ID만 빠르게 가져오기
    // 리턴값이 0L 이면 '실시간(Realtime) 모드'로 간주합니다.
    public long getCurrentSessionId() {
        if (currentSession == null || currentSession.getSessionId() == null) {
            return 0L; 
        }
        return currentSession.getSessionId();
    }
}