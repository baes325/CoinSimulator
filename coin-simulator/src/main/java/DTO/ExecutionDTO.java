package DTO;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.io.Serializable;

/*
 * Nullable 처리: buyAvgPrice, realizedPnl, roi는 매수(BID) 체결 시에는 보통 null이거나 0입니다. 
 * (수익은 매도할 때 확정되므로). DB에서 가져올 때 rs.getBigDecimal("realized_pnl")을 하면 null 체크를 잘 해야 합니다.

	생성자 분리: 모든 필드를 다 넣는 생성자보다, 위 예시처럼 '체결 발생 시점에 꼭 필요한 데이터'만 받는 생성자를 따로 
	만들어두면 서비스 로직(Service Layer)에서 객체를 생성할 때 훨씬 편합니다.
*/

public class ExecutionDTO {

	private static final long serialVersionUID = 1L;

    // 1. PK & FK
    private Long executionId;      // execution_id
    private Long orderId;          // order_id (어떤 주문의 체결인지)

    // 2. 체결 기본 정보
    private String market;         // market (예: KRW-BTC)
    private String side;           // side ('BID', 'ASK') - 추후 Enum으로 변경 가능
    
    // 3. 체결 수치 (정밀 계산용 BigDecimal)
    private BigDecimal price;      // price (체결가)
    private BigDecimal volume;     // volume (체결 수량)
    private BigDecimal fee;        // fee (수수료)

    // 4. 손익 분석 데이터 (매도 시에만 값이 존재할 수 있음 -> Nullable)
    private BigDecimal buyAvgPrice; // buy_avg_price (당시 평단가)
    private BigDecimal realizedPnl; // realized_pnl (실현 손익)
    private BigDecimal roi;         // roi (수익률)

    // 5. 시간
    private Timestamp executedAt;  // executed_at

    // --- 생성자 (Constructors) ---

    // 1. 기본 생성자
    public ExecutionDTO() {
    }

    // 2. 필수 데이터 생성자 (체결 발생 시 주로 사용)
    public ExecutionDTO(Long orderId, String market, String side, 
                        BigDecimal price, BigDecimal volume, BigDecimal fee) {
        this.orderId = orderId;
        this.market = market;
        this.side = side;
        this.price = price;
        this.volume = volume;
        this.fee = fee;
    }

    // --- 디버깅용 toString ---
    @Override
    public String toString() {
        return "ExecutionDTO [executionId=" + executionId + ", market=" + market + 
               ", side=" + side + ", price=" + price + ", volume=" + volume + 
               ", pnl=" + realizedPnl + "]";
    }
    
    // --- Getter & Setter 영역 ---
	public Long getExecutionId() {
		return executionId;
	}

	public void setExecutionId(Long executionId) {
		this.executionId = executionId;
	}

	public Long getOrderId() {
		return orderId;
	}

	public void setOrderId(Long orderId) {
		this.orderId = orderId;
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

	public BigDecimal getPrice() {
		return price;
	}

	public void setPrice(BigDecimal price) {
		this.price = price;
	}

	public BigDecimal getVolume() {
		return volume;
	}

	public void setVolume(BigDecimal volume) {
		this.volume = volume;
	}

	public BigDecimal getFee() {
		return fee;
	}

	public void setFee(BigDecimal fee) {
		this.fee = fee;
	}

	public BigDecimal getBuyAvgPrice() {
		return buyAvgPrice;
	}

	public void setBuyAvgPrice(BigDecimal buyAvgPrice) {
		this.buyAvgPrice = buyAvgPrice;
	}

	public BigDecimal getRealizedPnl() {
		return realizedPnl;
	}

	public void setRealizedPnl(BigDecimal realizedPnl) {
		this.realizedPnl = realizedPnl;
	}

	public BigDecimal getRoi() {
		return roi;
	}

	public void setRoi(BigDecimal roi) {
		this.roi = roi;
	}

	public Timestamp getExecutedAt() {
		return executedAt;
	}

	public void setExecutedAt(Timestamp executedAt) {
		this.executedAt = executedAt;
	}

	public static long getSerialversionuid() {
		return serialVersionUID;
	}

}
