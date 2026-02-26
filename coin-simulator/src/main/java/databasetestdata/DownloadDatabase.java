package databasetestdata;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.json.JSONArray;
import org.json.JSONObject;

import com.team.coin_simulator.CoinConfig;
import com.team.coin_simulator.DBConnection;

public class DownloadDatabase {

    // 초당 8~9회 호출 제한 및 429 발생 시 전역 딜레이 처리를 위한 AtomicLong
    private static final AtomicLong LAST_REQUEST_TIME = new AtomicLong(0);
    private static final long MIN_REQUEST_INTERVAL_MS = 120;
    
    // 스레드 풀 재사용 (매번 생성/파기하는 오버헤드 제거)
    private static final ExecutorService WORKER_POOL = Executors.newFixedThreadPool(3);

    private static final DateTimeFormatter FORMATTER     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter API_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    public enum CandleType {
        MIN_1  (1,     "minutes", 1),
        MIN_30 (30,    "minutes", 30),
        HOUR_1 (60,    "minutes", 60),
        DAY    (1440,  "days",    -1),
        MONTH  (43200, "months",  -1);

        final int    dbUnit;
        final String apiType;
        final int    apiUnit;

        CandleType(int dbUnit, String apiType, int apiUnit) {
            this.dbUnit  = dbUnit;
            this.apiType = apiType;
            this.apiUnit = apiUnit;
        }
    }

    // =====================================================================
    //  PUBLIC API
    // =====================================================================

    public static void updateAll() {
        for (CandleType type : CandleType.values()) {
            updateData(type);
        }
    }

    public static void updateData(CandleType type) {
        System.out.println("\n=== [" + type.name() + "] 업데이트 시작 ===");
        
        CountDownLatch latch = new CountDownLatch(CoinConfig.getCodes().size());
        
        for (String code : CoinConfig.getCodes()) {
            final String market = "KRW-" + code;
            WORKER_POOL.submit(() -> {
                try {
                    System.out.println("[" + market + "] 업데이트 확인 중...");
                    updateCoinHistory(market, type);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("=== [" + type.name() + "] 업데이트 완료 ===");
    }

    public static void importAll() {
        for (CandleType type : CandleType.values()) {
            importData(type);
        }
    }

    public static void importData(CandleType type) {
        LocalDateTime now        = LocalDateTime.now();
        LocalDateTime cutoffDate = now.minusMonths(6);

        System.out.println("\n=== [" + type.name() + "] 과거 데이터 수집 시작 ===");
        System.out.println("목표 기간: " + now.format(FORMATTER) + " ~ " + cutoffDate.format(FORMATTER));

        CountDownLatch latch = new CountDownLatch(CoinConfig.getCodes().size());

        for (String code : CoinConfig.getCodes()) {
            final String market = "KRW-" + code;
            WORKER_POOL.submit(() -> {
                try {
                    System.out.println("[" + market + "] 수집 시작...");
                    crawlCoinHistory(market, type, cutoffDate);
                } finally {
                    latch.countDown();
                }
            });
        }
        
        try {
            latch.await(120, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        System.out.println("=== [" + type.name() + "] 수집 완료 ===");
    }

    // =====================================================================
    //  PRIVATE - UPDATE & IMPORT
    // =====================================================================

    private static void updateCoinHistory(String market, CandleType type) {
        LocalDateTime lastSavedDate = getMaxDate(market, type.dbUnit);
        if (lastSavedDate == null) {
            System.out.println(" ↳ 기존 데이터 없음 → 자동으로 6개월치 수집 시작");
            crawlCoinHistory(market, type, LocalDateTime.now().minusMonths(6));
            return;
        }
        System.out.println(" ↳ 마지막 저장 시간: " + lastSavedDate.format(FORMATTER));

        String  toDate     = "";
        boolean isFinished = false;
        int     totalSaved = 0;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(buildInsertSql())) {

            conn.setAutoCommit(false);

            while (!isFinished) {
                try {
                    JSONArray candles = fetchCandles(market, type, 200, toDate);
                    if (candles.isEmpty()) break;

                    int batchCount = 0;
                    for (int i = 0; i < candles.length(); i++) {
                        JSONObject c      = candles.getJSONObject(i);
                        String     utcRaw = c.getString("candle_date_time_utc");
                        LocalDateTime date = parseUtc(utcRaw);

                        if (!date.isAfter(lastSavedDate)) {
                            isFinished = true;
                            break;
                        }

                        setPstmt(pstmt, market, type.dbUnit, c, utcRaw);
                        pstmt.addBatch();
                        batchCount++;
                    }

                    if (batchCount > 0) {
                        pstmt.executeBatch();
                        pstmt.clearBatch();
                        conn.commit(); // 배치 단위 즉시 커밋 (데이터 유실 방지)
                        totalSaved += batchCount;
                        System.out.print(".");
                    }

                    if (!isFinished) {
                        JSONObject oldest = candles.getJSONObject(candles.length() - 1);
                        LocalDateTime oldestTime = parseUtc(oldest.getString("candle_date_time_utc"));
                        // 무한 루프 방지: 가장 오래된 시간에서 1초 차감 후 다음 페이지 요청
                        toDate = oldestTime.minusSeconds(1).format(API_FORMATTER) + "Z";
                    }

                } catch (Exception e) {
                    if (handleRateLimitException(e, market)) continue;
                    System.err.println("\n[Error] " + market + " 업데이트 중단: " + e.getMessage());
                    try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
                    break;
                }
            }
            System.out.println(" 완료 (새로 추가: " + totalSaved + "개)");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void crawlCoinHistory(String market, CandleType type, LocalDateTime cutoffDate) {
        String toDate = "";
        LocalDateTime minDate = getMinDate(market, type.dbUnit);
        if (minDate != null) {
            toDate = minDate.format(API_FORMATTER) + "Z";
            System.out.println(" ↳ 기존 데이터 발견! [" + toDate + "] 부터 이어서 수집");
        } else {
            System.out.println(" ↳ 기존 데이터 없음. 현재부터 수집 시작");
        }

        boolean isFinished = false;
        int     totalSaved = 0;

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(buildInsertSql())) {

            conn.setAutoCommit(false);

            while (!isFinished) {
                try {
                    JSONArray candles = fetchCandles(market, type, 200, toDate);
                    if (candles.isEmpty()) break;

                    for (int i = 0; i < candles.length(); i++) {
                        JSONObject c      = candles.getJSONObject(i);
                        String     utcRaw = c.getString("candle_date_time_utc");
                        setPstmt(pstmt, market, type.dbUnit, c, utcRaw);
                        pstmt.addBatch();
                    }

                    pstmt.executeBatch();
                    pstmt.clearBatch();
                    conn.commit(); // 배치 단위 즉시 커밋 (데이터 유실 방지)
                    totalSaved += candles.length();
                    System.out.print(".");

                    JSONObject oldest = candles.getJSONObject(candles.length() - 1);
                    LocalDateTime oldestTime = parseUtc(oldest.getString("candle_date_time_utc"));
                    
                    if (oldestTime.isBefore(cutoffDate)) {
                        isFinished = true;
                    } else {
                        // 무한 루프 방지: 가장 오래된 시간에서 1초 차감 후 다음 페이지 요청
                        toDate = oldestTime.minusSeconds(1).format(API_FORMATTER) + "Z";
                    }

                } catch (Exception e) {
                    if (handleRateLimitException(e, market)) continue;
                    System.err.println("\n[Error] " + market + " 수집 중단: " + e.getMessage());
                    try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
                    break;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        System.out.println(" 완료 (총 " + totalSaved + "개 추가)");
    }

    // =====================================================================
    //  PRIVATE - 업비트 API 및 유틸
    // =====================================================================

    private static JSONArray fetchCandles(String market, CandleType type, int count, String toDate) throws Exception {
        StringBuilder url = new StringBuilder("https://api.upbit.com/v1/candles/");
        url.append(type.apiType);

        if ("minutes".equals(type.apiType)) {
            url.append("/").append(type.apiUnit);
        }

        url.append("?market=").append(market).append("&count=").append(count);

        if (toDate != null && !toDate.isEmpty()) {
            url.append("&to=").append(URLEncoder.encode(toDate, "UTF-8"));
        }

        synchronized (LAST_REQUEST_TIME) {
            long elapsed = System.currentTimeMillis() - LAST_REQUEST_TIME.get();
            if (elapsed < MIN_REQUEST_INTERVAL_MS) {
                Thread.sleep(MIN_REQUEST_INTERVAL_MS - elapsed);
            }
            LAST_REQUEST_TIME.set(System.currentTimeMillis());
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json");

            int status = conn.getResponseCode();
            if (status != 200) {
                throw new RuntimeException("HTTP Error: " + status + " | URL: " + url);
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
                return new JSONArray(sb.toString());
            }
        } finally {
            conn.disconnect();
        }
    }

    private static boolean handleRateLimitException(Exception e, String market) {
        String errMsg = e.getMessage();
        if (errMsg != null && errMsg.contains("429")) {
            System.err.println("\n[RateLimit] " + market + " 429 에러 발생. 전역 API 호출 5초 대기 적용...");
            // 전역 시간을 미래로 밀어버림으로써 모든 스레드가 깔끔하게 동시 대기하도록 처리
            LAST_REQUEST_TIME.set(System.currentTimeMillis() + 5000);
            return true;
        }
        return false;
    }

    private static LocalDateTime getMaxDate(String market, int unit) {
        return queryDate(market, unit, "MAX");
    }

    private static LocalDateTime getMinDate(String market, int unit) {
        return queryDate(market, unit, "MIN");
    }

    private static LocalDateTime queryDate(String market, int unit, String func) {
        String sql = "SELECT " + func + "(candle_date_time_utc) FROM market_candle WHERE market = ? AND unit = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, market);
            stmt.setInt(2, unit);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getObject(1) != null) {
                    return rs.getObject(1, LocalDateTime.class);
                }
            }
        } catch (Exception e) {
            System.err.println("DB 조회 실패: " + e.getMessage());
        }
        return null;
    }

    private static String buildInsertSql() {
        return "INSERT INTO market_candle " +
               "(market, candle_date_time_utc, candle_date_time_kst, opening_price, high_price, low_price, " +
               "trade_price, timestamp, candle_acc_trade_price, candle_acc_trade_volume, unit) " +
               "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
               "ON DUPLICATE KEY UPDATE " +
               "opening_price = VALUES(opening_price), " +
               "high_price = VALUES(high_price), " +
               "low_price = VALUES(low_price), " +
               "trade_price = VALUES(trade_price), " +
               "candle_acc_trade_price = VALUES(candle_acc_trade_price), " +
               "candle_acc_trade_volume = VALUES(candle_acc_trade_volume)";
    }

    private static void setPstmt(PreparedStatement pstmt, String market, int dbUnit, JSONObject c, String utcRaw) throws Exception {
        pstmt.setString(1, market);
        pstmt.setObject(2, LocalDateTime.parse(utcRaw.replace("T", " "), FORMATTER));
        pstmt.setObject(3, LocalDateTime.parse(c.getString("candle_date_time_kst").replace("T", " "), FORMATTER));
        pstmt.setBigDecimal(4, c.getBigDecimal("opening_price"));
        pstmt.setBigDecimal(5, c.getBigDecimal("high_price"));
        pstmt.setBigDecimal(6, c.getBigDecimal("low_price"));
        pstmt.setBigDecimal(7, c.getBigDecimal("trade_price"));
        pstmt.setLong(8, c.getLong("timestamp"));
        pstmt.setBigDecimal(9, c.getBigDecimal("candle_acc_trade_price"));
        pstmt.setBigDecimal(10, c.getBigDecimal("candle_acc_trade_volume"));
        pstmt.setInt(11, dbUnit);
    }

    private static LocalDateTime parseUtc(String utcRaw) {
        return LocalDateTime.parse(utcRaw.replace("T", " "), FORMATTER);
    }

    // =====================================================================
    //  MAIN - 스케줄러
    // =====================================================================

    public static void main(String[] args) {
        System.out.println("캔들 데이터 동기화 스케줄러 시작...");

        // 단일 스레드 병목 해결: 5개의 스케줄을 독립적으로 처리할 스레드 풀 할당
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Scheduler] 종료 신호 수신 → 스케줄러 및 워커풀 안전 종료 중...");
            scheduler.shutdown();
            WORKER_POOL.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS)) scheduler.shutdownNow();
                if (!WORKER_POOL.awaitTermination(10, TimeUnit.SECONDS)) WORKER_POOL.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                WORKER_POOL.shutdownNow();
            }
        }));

        scheduler.scheduleWithFixedDelay(() -> {
            try { updateData(CandleType.MIN_1); } catch (Exception e) { System.err.println("[1분봉] 오류: " + e.getMessage()); }
        }, 0, 60, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            try { updateData(CandleType.MIN_30); } catch (Exception e) { System.err.println("[30분봉] 오류: " + e.getMessage()); }
        }, 10, 1800, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            try { updateData(CandleType.HOUR_1); } catch (Exception e) { System.err.println("[1시간봉] 오류: " + e.getMessage()); }
        }, 20, 3600, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            try { updateData(CandleType.DAY); } catch (Exception e) { System.err.println("[일봉] 오류: " + e.getMessage()); }
        }, 30, 86400, TimeUnit.SECONDS);

        scheduler.scheduleWithFixedDelay(() -> {
            try { updateData(CandleType.MONTH); } catch (Exception e) { System.err.println("[한달봉] 오류: " + e.getMessage()); }
        }, 40, 86400, TimeUnit.SECONDS);
    }
}