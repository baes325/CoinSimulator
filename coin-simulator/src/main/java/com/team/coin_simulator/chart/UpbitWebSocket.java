package com.team.coin_simulator.chart;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

public class UpbitWebSocket implements WebSocket.Listener {

    private final String market;
    private final CandleChartPanel chartPanel;
    private WebSocket webSocket;

    public UpbitWebSocket(String market, CandleChartPanel chartPanel) {
        this.market = market;
        this.chartPanel = chartPanel;
    }

    // 웹소켓 연결 시작
    public void connect() {
        HttpClient client = HttpClient.newHttpClient();
        client.newWebSocketBuilder()
              .buildAsync(URI.create("wss://api.upbit.com/websocket/v1"), this)
              .thenAccept(ws -> this.webSocket = ws)
              .exceptionally(throwable -> {
                  throwable.printStackTrace();
                  return null;
              });
    }

    // 웹소켓 연결 해제
    public void disconnect() {
        if (webSocket != null) {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "User closed");
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        System.out.println("웹소켓 연결 성공 : " + market);
        
        // 업비트에 구독(Subscription) 요청 전송
        String requestJson = "[{\"ticket\":\"simulator-test\"},{\"type\":\"ticker\",\"codes\":[\"" + market + "\"]}]";
        webSocket.sendText(requestJson, true);
        
        // 다음 메시지를 받기 위해 반드시 호출
        WebSocket.Listener.super.onOpen(webSocket);
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        // 1. 업비트에서 온 바이너리 데이터를 문자열로 변환
        String message = StandardCharsets.UTF_8.decode(data).toString();
        
        // 2. 외부 라이브러리 없이 직접 문자열에서 "trade_price" 값 추출
        double tradePrice = extractTradePrice(message);
        
        // 3. 차트 패널에 실시간 가격 업데이트
        if (tradePrice > 0 && chartPanel != null) {
            // 변경: 종목 코드도 함께 전달
            chartPanel.setLatestPriceFromWebSocket(this.market, tradePrice);
        }

        // 다음 메시지를 계속 수신하기 위해 호출
        webSocket.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        System.out.println("웹소켓 종료됨: " + reason);
        return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        error.printStackTrace();
        WebSocket.Listener.super.onError(webSocket, error);
    }

    /**
     * JSON 라이브러리 없이 순수 문자열 조작으로 trade_price 추출하는 헬퍼 메서드
     */
    private double extractTradePrice(String json) {
        String targetKey = "\"trade_price\":";
        int startIndex = json.indexOf(targetKey);
        
        if (startIndex != -1) {
            startIndex += targetKey.length();
            
            // 값의 끝부분(콤마 또는 닫는 중괄호) 찾기
            int endIndex = json.indexOf(",", startIndex);
            if (endIndex == -1) {
                endIndex = json.indexOf("}", startIndex);
            }
            
            if (endIndex != -1) {
                String priceStr = json.substring(startIndex, endIndex).trim();
                try {
                    return Double.parseDouble(priceStr);
                } catch (NumberFormatException e) {
                    // 변환 실패 시 무시
                }
            }
        }
        return -1; // 실패 시 음수 반환
    }
}