package DTO;

import java.math.BigDecimal;
import java.sql.Timestamp;

public class AutoOrderDTO {
    private long autoId;
    private String userId;
    private long sessionId;
    private String market;         // 예: KRW-BTC
    private BigDecimal triggerPrice; // 격발(목표) 가격
    private BigDecimal volume;       // 주문 수량
    private String conditionType;  // ABOVE(돌파), BELOW(이탈)
    private String side;           // ASK(매도), BID(매수)
    private boolean active;        // 활성화 여부
    private Timestamp createdAt;

    // Getter & Setter
    public long getAutoId() { return autoId; }
    public void setAutoId(long autoId) { this.autoId = autoId; }
    
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    
    public long getSessionId() { return sessionId; }
    public void setSessionId(long sessionId) { this.sessionId = sessionId; }
    
    public String getMarket() { return market; }
    public void setMarket(String market) { this.market = market; }
    
    public BigDecimal getTriggerPrice() { return triggerPrice; }
    public void setTriggerPrice(BigDecimal triggerPrice) { this.triggerPrice = triggerPrice; }
    
    public BigDecimal getVolume() { return volume; }
    public void setVolume(BigDecimal volume) { this.volume = volume; }
    
    public String getConditionType() { return conditionType; }
    public void setConditionType(String conditionType) { this.conditionType = conditionType; }
    
    public String getSide() { return side; }
    public void setSide(String side) { this.side = side; }
    
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    
    public Timestamp getCreatedAt() { return createdAt; }
    public void setCreatedAt(Timestamp createdAt) { this.createdAt = createdAt; }
}