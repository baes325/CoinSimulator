package databasetestdata;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import org.json.JSONArray;
import org.json.JSONObject;

import com.team.coin_simulator.CoinConfig;
import com.team.coin_simulator.DBConnection;

public class DownloadDatabase {

    private static final String UPBIT_URL_MINUTES = "https://api.upbit.com/v1/candles/minutes/";
    
    // 업비트 API 요청 시 날짜 포맷 (yyyy-MM-dd HH:mm:ss)
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * DB에 저장된 마지막 시간부터 현재 시점까지의 누락된 데이터를 보충(업데이트)합니다.
     * @param unit 분 단위 (예: 1, 3, 5, 15, 30, 60, 240)
     */
    public static void updateData(int unit) {
        System.out.println("=== 최신 데이터 업데이트 시작 ===");
        System.out.println("수집 단위: " + unit + "분봉");

        for (String code : CoinConfig.getCodes()) {
            String market = "KRW-" + code;
            System.out.println("\n[" + market + "] 업데이트 확인 중...");
            
            // 각 코인별로 DB의 마지막 시간을 확인하고 누락된 최신 데이터를 가져옵니다.
            updateCoinHistory(market, unit);
        }
        
        System.out.println("\n=== 모든 코인 데이터 업데이트 완료 ===");
    }

    private static void updateCoinHistory(String market, int unit) {
        LocalDateTime lastSavedDate = null;
        
        // 1. DB에서 해당 코인의 가장 최근(MAX) 날짜를 조회합니다.
        String checkSql = "SELECT MAX(candle_date_time_utc) FROM market_candle WHERE market = ? AND unit = ?";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
            
            checkStmt.setString(1, market);
            checkStmt.setInt(2, unit);
            try (java.sql.ResultSet rs = checkStmt.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    lastSavedDate = rs.getTimestamp(1).toLocalDateTime();
                    System.out.println(" ↳ 마지막 저장 시간: " + lastSavedDate.format(FORMATTER) + " -> 현재까지 보충 수집 진행");
                } else {
                    System.out.println(" ↳ 기존 데이터가 없습니다. 업데이트 대신 전체 수집(importData)을 진행해 주세요.");
                    return; // 데이터가 아예 없으면 업데이트를 건너뜁니다.
                }
            }
        } catch (Exception e) {
            System.err.println("DB 조회 실패: " + e.getMessage());
            return;
        }

        // 2. 현재 시간부터 과거로 거슬러 올라가며 데이터 수집 (lastSavedDate에 도달할 때까지)
        String toDate = "";  // 처음엔 빈 값으로 보내어 '현재 시간' 기준으로 가져옴
        boolean isFinished = false;
        int totalSaved = 0;

        String insertSql = "INSERT INTO market_candle " +
                     "(market, candle_date_time_utc, candle_date_time_kst, opening_price, high_price, low_price, " +
                     "trade_price, timestamp, candle_acc_trade_price, candle_acc_trade_volume, unit) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE trade_price = VALUES(trade_price)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(insertSql)) {

            conn.setAutoCommit(false); 

            while (!isFinished) {
                try {
                    // API 호출
                    String jsonResponse = fetchCandles(market, unit, 200, toDate);
                    JSONArray candles = new JSONArray(jsonResponse);

                    if (candles.isEmpty()) {
                        break; 
                    }

                    int batchCount = 0;

                    for (int i = 0; i < candles.length(); i++) {
                        JSONObject c = candles.getJSONObject(i);
                        String utcRaw = c.getString("candle_date_time_utc"); 
                        LocalDateTime candleDate = LocalDateTime.parse(utcRaw.replace("T", " "), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                        // 핵심 로직: 수집한 캔들 시간이 DB에 있는 마지막 시간보다 작거나 같아지면 (즉, 이미 있는 데이터면) 수집 중단
                        if (!candleDate.isAfter(lastSavedDate)) {
                            isFinished = true;
                            break; // for 루프 탈출
                        }

                        pstmt.setString(1, market);
                        pstmt.setTimestamp(2, Timestamp.valueOf(utcRaw.replace("T", " ")));
                        pstmt.setTimestamp(3, Timestamp.valueOf(c.getString("candle_date_time_kst").replace("T", " ")));
                        pstmt.setBigDecimal(4, c.getBigDecimal("opening_price"));
                        pstmt.setBigDecimal(5, c.getBigDecimal("high_price"));
                        pstmt.setBigDecimal(6, c.getBigDecimal("low_price"));
                        pstmt.setBigDecimal(7, c.getBigDecimal("trade_price"));
                        pstmt.setLong(8, c.getLong("timestamp"));
                        pstmt.setBigDecimal(9, c.getBigDecimal("candle_acc_trade_price"));
                        pstmt.setBigDecimal(10, c.getBigDecimal("candle_acc_trade_volume"));
                        pstmt.setInt(11, unit);
                        pstmt.addBatch();
                        batchCount++;
                    }
                    
                    if (batchCount > 0) {
                        pstmt.executeBatch(); 
                        pstmt.clearBatch();
                        totalSaved += batchCount;
                        System.out.print("."); 
                    }

                    // 다음 페이지(더 과거) 조회를 위해 이번 요청에서 받은 가장 마지막(과거) 캔들의 시간을 toDate로 설정
                    JSONObject lastCandle = candles.getJSONObject(candles.length() - 1);
                    toDate = lastCandle.getString("candle_date_time_utc") + "Z"; 

                    Thread.sleep(120); // API 호출 제한 방지 (Rate Limit)

                } catch (Exception e) {
                    System.err.println("\n[Error] " + market + " 업데이트 중단: " + e.getMessage());
                    break; 
                }
            }
            
            conn.commit(); 
            System.out.println(" 완료 (새로 추가된 데이터: " + totalSaved + "개)");
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /**
     * 등록된 모든 코인의 6개월 치 데이터를 수집합니다.
     * @param unit 분 단위 (예: 1, 3, 5, 15, 30, 60, 240)
     */
    public static void importData(int unit) {
        // 1. 수집 종료 시점 설정 (현재로부터 6개월 전)
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime cutoffDate = now.minusMonths(6); 
        
        System.out.println("=== 과거 데이터 수집 시작 ===");
        System.out.println("목표 기간: " + now.format(FORMATTER) + " ~ " + cutoffDate.format(FORMATTER));
        System.out.println("수집 단위: " + unit + "분봉");

        for (String code : CoinConfig.getCodes()) {
            String market = "KRW-" + code;
            System.out.println("\n[" + market + "] 데이터 수집 시작...");
            
            // 각 코인별로 현재 시간부터 과거로 이동하며 수집
            crawlCoinHistory(market, unit, cutoffDate);
        }
        
        System.out.println("\n=== 모든 코인 데이터 수집 완료 ===");
    }

    private static void crawlCoinHistory(String market, int unit, LocalDateTime cutoffDate) {
        String toDate = ""; 
        boolean isFinished = false;
        int totalSaved = 0;

        String sql = "INSERT INTO market_candle " +
                     "(market, candle_date_time_utc, candle_date_time_kst, opening_price, high_price, low_price, " +
                     "trade_price, timestamp, candle_acc_trade_price, candle_acc_trade_volume, unit) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE trade_price = VALUES(trade_price)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false); 

            // 🚨 [추가된 로직] DB에서 해당 코인의 가장 오래된(MIN) 날짜를 조회합니다.
            String checkSql = "SELECT MIN(candle_date_time_utc) FROM market_candle WHERE market = ? AND unit = ?";
            try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                checkStmt.setString(1, market);
                checkStmt.setInt(2, unit);
                try (java.sql.ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next() && rs.getTimestamp(1) != null) {
                        // 저장된 데이터가 있다면 그 시간부터 과거로 이어서 수집
                        LocalDateTime oldestLdt = rs.getTimestamp(1).toLocalDateTime();
                        toDate = oldestLdt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + "Z";
                        System.out.println(" ↳ 기존 데이터 발견! [" + toDate + "] 부터 이어서 수집합니다.");
                    } else {
                        System.out.println(" ↳ 기존 데이터 없음. 최신(현재)부터 수집을 시작합니다.");
                    }
                }
            }

            while (!isFinished) {
                try {
                    String jsonResponse = fetchCandles(market, unit, 200, toDate);
                    JSONArray candles = new JSONArray(jsonResponse);

                    // ... (이하 기존 코드와 동일) ...
                    
                    if (candles.isEmpty()) {
                        break; 
                    }

                    JSONObject lastCandle = candles.getJSONObject(candles.length() - 1);
                    String utcRaw = lastCandle.getString("candle_date_time_utc"); 
                    toDate = utcRaw + "Z"; 
                    
                    LocalDateTime lastDate = LocalDateTime.parse(utcRaw.replace("T", " "), DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

                    for (int i = 0; i < candles.length(); i++) {
                        JSONObject c = candles.getJSONObject(i);
                        pstmt.setString(1, market);
                        pstmt.setTimestamp(2, Timestamp.valueOf(c.getString("candle_date_time_utc").replace("T", " ")));
                        pstmt.setTimestamp(3, Timestamp.valueOf(c.getString("candle_date_time_kst").replace("T", " ")));
                        pstmt.setBigDecimal(4, c.getBigDecimal("opening_price"));
                        pstmt.setBigDecimal(5, c.getBigDecimal("high_price"));
                        pstmt.setBigDecimal(6, c.getBigDecimal("low_price"));
                        pstmt.setBigDecimal(7, c.getBigDecimal("trade_price"));
                        pstmt.setLong(8, c.getLong("timestamp"));
                        pstmt.setBigDecimal(9, c.getBigDecimal("candle_acc_trade_price"));
                        pstmt.setBigDecimal(10, c.getBigDecimal("candle_acc_trade_volume"));
                        pstmt.setInt(11, unit);
                        pstmt.addBatch();
                    }
                    
                    pstmt.executeBatch(); 
                    pstmt.clearBatch();
                    
                    totalSaved += candles.length();
                    System.out.print("."); 

                    if (lastDate.isBefore(cutoffDate)) {
                        isFinished = true;
                    }

                    Thread.sleep(120); 

                } catch (Exception e) {
                    System.err.println("\n[Error] " + market + " 수집 중단: " + e.getMessage());
                    break; 
                }
            }
            
            conn.commit(); 
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println(" 완료 (총 " + totalSaved + "개 추가됨)");
    }

    private static String fetchCandles(String market, int unit, int count, String toDate) throws Exception {
        StringBuilder urlBuilder = new StringBuilder("https://api.upbit.com/v1/candles/minutes/");
        urlBuilder.append(unit)
                  .append("?market=").append(market)
                  .append("&count=").append(count);

        if (toDate != null && !toDate.isEmpty()) {
            // 🚨 핵심 3: 날짜 데이터에 들어간 특수문자(:, T, Z)가 URL에서 꼬이지 않게 URL 인코딩 적용
            String encodedTo = toDate.replace(":", "%3A").replace(" ", "%20");
            urlBuilder.append("&to=").append(encodedTo);
        }

        URL url = new URL(urlBuilder.toString());
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        if (conn.getResponseCode() != 200) {
            throw new RuntimeException("HTTP Error: " + conn.getResponseCode()); 
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }

    private static void saveBatch(String market, int unit, JSONArray candles) {
        String sql = "INSERT INTO market_candle " +
                     "(market, candle_date_time_utc, candle_date_time_kst, opening_price, high_price, low_price, " +
                     "trade_price, timestamp, candle_acc_trade_price, candle_acc_trade_volume, unit) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                     "ON DUPLICATE KEY UPDATE " +
                     "trade_price = VALUES(trade_price)"; // 이미 있으면 업데이트

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < candles.length(); i++) {
                JSONObject c = candles.getJSONObject(i);
                
                String utcStr = c.getString("candle_date_time_utc").replace("T", " ");
                String kstStr = c.getString("candle_date_time_kst").replace("T", " ");

                int idx = 1;
                pstmt.setString(idx++, market);
                pstmt.setTimestamp(idx++, Timestamp.valueOf(utcStr));
                pstmt.setTimestamp(idx++, Timestamp.valueOf(kstStr));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("opening_price"));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("high_price"));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("low_price"));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("trade_price"));
                pstmt.setLong(idx++, c.getLong("timestamp"));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("candle_acc_trade_price"));
                pstmt.setBigDecimal(idx++, c.getBigDecimal("candle_acc_trade_volume"));
                pstmt.setInt(idx++, unit);

                pstmt.addBatch();
            }
            pstmt.executeBatch();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    
    
    public static void main(String[] args) {
        // DB 연결 확인 (필요 시 initDatabase)
    	System.out.println(">>> 데이터 수집 프로세스 시작 <<<");
        // 6개월 치 데이터 수집 실행
        // unit: 1(1분), 60(1시간), 240(4시간) 권장
        importData(1); 
    }
}