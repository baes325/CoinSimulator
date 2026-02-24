package DTO;

import java.math.BigDecimal;
import java.sql.Timestamp;



public class OrderDTO {
	// 1. PK
    private Long orderId;

    // 2. 식별자
    private String userId;
    private Long sessionId;

    // 3. 주문 정보
    private String market;          // KRW-BTC
    private String side;            // BID(매수), ASK(매도) -> String 대신 enum 추천
    private String type;            // LIMIT, MARKET -> String 대신 enum 추천

    // 4. 가격/수량 (돈 계산은 무조건 BigDecimal)
    private BigDecimal originalPrice;
    private BigDecimal originalVolume;
    
    public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
	}

	public String getUserId() {
		return userId;
	}

	public void setUserId(String userId) {
		this.userId = userId;
	}

	public Long getSessionId() {
		return sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public String getMarket() {
		return market;
	}

	public void setMarket(String market) {
		this.market = market;
	}

	public String getSide() {
		return side;
	}

	public void setSide(String side) {
		this.side = side;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public BigDecimal getOriginalPrice() {
		return originalPrice;
	}

	public void setOriginalPrice(BigDecimal originalPrice) {
		this.originalPrice = originalPrice;
	}

	public BigDecimal getOriginalVolume() {
		return originalVolume;
	}

	public void setOriginalVolume(BigDecimal originalVolume) {
		this.originalVolume = originalVolume;
	}

	public BigDecimal getRemainingVolume() {
		return remainingVolume;
	}

	public void setRemainingVolume(BigDecimal remainingVolume) {
		this.remainingVolume = remainingVolume;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public Timestamp getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(Timestamp createdAt) {
		this.createdAt = createdAt;
	}

	private BigDecimal remainingVolume;

    // 5. 상태 및 시간
    private String status;          // WAIT, DONE, CANCEL
    private Timestamp createdAt;

    // 기본 생성자
    public OrderDTO() {}

   
}
