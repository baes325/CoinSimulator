package DAO; // 패키지명 주의 (User코드 기준)
// 또는 package DAO; 사용 중인 패키지에 맞게 조정하세요.

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.team.coin_simulator.CoinConfig;

import DTO.TickerDto;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class UpbitWebSocketDao extends WebSocketListener {
    
    // 1. 싱글톤 인스턴스
    private static UpbitWebSocketDao instance;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private WebSocket webSocket;
    
    // 2. 현재가 저장소 (모든 패널에서 공유)
    private static final Map<String, BigDecimal> currentPriceMap = new ConcurrentHashMap<>();
    
    // 3. 리스너 목록 (데이터를 받고 싶은 UI들)
    private List<TickerListener> listeners = new ArrayList<>();

    // 4. 리스너 인터페이스 정의
    public interface TickerListener {
        void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr, String tradeVolumeStr);
    }

    // 싱글톤 접근 메서드
    public static synchronized UpbitWebSocketDao getInstance() {
        if (instance == null) {
            instance = new UpbitWebSocketDao();
        }
        return instance;
    }

    // 생성자 (private)
    private UpbitWebSocketDao() {
        // 생성 시 바로 연결하지 않고 start() 호출 시 연결하거나, 여기서 바로 연결해도 됨
    }

    // 웹소켓 시작 메서드 (앱 실행 시 한 번만 호출)
    public void start() {
        if (webSocket != null) return; // 이미 실행 중이면 패스

        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("wss://api.upbit.com/websocket/v1")
                .build();
        webSocket = client.newWebSocket(request, this);
    }

    // 리스너 등록
    public void addListener(TickerListener listener) {
        listeners.add(listener);
    }
    
    // 현재가 조회 (자산 패널 등 계산용)
    public static BigDecimal getCurrentPrice(String market) { // KRW-BTC 등
        return currentPriceMap.getOrDefault(market, BigDecimal.ZERO);
    }

    private String getSubscriptionMessage() {
        String codes = CoinConfig.getCodes().stream()
                .map(coin -> "\"KRW-" + coin + "\"")
                .collect(Collectors.joining(","));
        return String.format("[{\"ticket\":\"test\"},{\"type\":\"ticker\",\"codes\":[%s]}]", codes);
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        webSocket.send(getSubscriptionMessage());
        System.out.println("Upbit WebSocket Connected.");
    }

    @Override
    public void onMessage(WebSocket webSocket, ByteString bytes) {
        try {
            String json = bytes.utf8();
            TickerDto data = objectMapper.readValue(json, TickerDto.class);
            
            // 1. 현재가 Map 업데이트 (raw data)
            if (data.getCode() != null) {
                currentPriceMap.put(data.getCode(), BigDecimal.valueOf(data.getTrade_price()));
            }

            // 2. UI 업데이트용 포맷팅 (기존 로직 유지)
            SwingUtilities.invokeLater(() -> {
                if (data.getCode() == null) return;

                String symbol = data.getCode().replace("KRW-", "");
                double price = data.getTrade_price();
                double accPrice = data.getAcc_trade_price();
                
                // 💡 [핵심 추가] JSON 객체(TickerDto)에서 이번 틱의 거래량(trade_volume)을 가져옵니다.
                // 만약 TickerDto에 getTrade_volume()이 없다면 에러가 날 수 있으니, DTO에 해당 필드를 꼭 추가해주세요!
                double tradeVolume = data.getTrade_volume(); 
                String tradeVolumeStr = String.valueOf(tradeVolume);

                String priceStr;
                if (price < 1) priceStr = String.format("%,.5f", price);
                else if (price < 100) priceStr = String.format("%,.2f", price);
                else priceStr = String.format("%,.0f", price);

                String accPriceStr;
                if (accPrice >= 100_000_000) accPriceStr = String.format("%,.0f백만", accPrice / 1_000_000);
                else accPriceStr = String.format("%,.0f", accPrice);

                String flucStr = String.format("%.2f", data.getSigned_change_rate() * 100);

                // 3. 등록된 모든 리스너에게 뿌리기 (🔥 파라미터 5개로 늘어남!)
                for (TickerListener listener : listeners) {
                    listener.onTickerUpdate(symbol, priceStr, flucStr, accPriceStr, tradeVolumeStr);
                }
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    // 종료 시
    public void close() {
        if (webSocket != null) {
            webSocket.close(1000, "Closed by user");
            webSocket = null;
        }
    }
}