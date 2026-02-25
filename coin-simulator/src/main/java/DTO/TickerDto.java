package DTO;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class TickerDto {

    private String code;                // 종목 구분 (예: KRW-BTC)
    private double trade_price;         // 현재가
    private double signed_change_rate;  // 전일 종가 대비 가격 변화율
    private double acc_trade_price;     // 총 거래대금 
    
    //업비트 웹소켓에서 보내주는 '가장 최근 체결량'을 담을 변수
    private double trade_volume;        
    
    public String getCode() {
        return code;
    }
    public void setCode(String code) {
        this.code = code;
    }
    public double getTrade_price() {
        return trade_price;
    }
    public void setTrade_price(double trade_price) {
        this.trade_price = trade_price;
    }
    public double getSigned_change_rate() {
        return signed_change_rate;
    }
    public void setSigned_change_rate(double signed_change_rate) {
        this.signed_change_rate = signed_change_rate;
    }
    public double getAcc_trade_price() {
        return acc_trade_price;
    }
    public void setAcc_trade_price(double acc_trade_price) {
        this.acc_trade_price = acc_trade_price;
    }
    
    // 💡 trade_volume에 대한 Getter / Setter 추가
    public double getTrade_volume() {
        return trade_volume;
    }
    public void setTrade_volume(double trade_volume) {
        this.trade_volume = trade_volume;
    }
}