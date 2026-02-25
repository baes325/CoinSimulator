package databasetestdata;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.json.JSONArray;
import org.json.JSONObject;

import com.team.coin_simulator.CoinConfig;
import com.team.coin_simulator.DBConnection;

public class DownloadDatabase {

    private static final DateTimeFormatter FORMATTER     = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter API_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    /**
     * 캔들 타입 정의
     *
     * DB unit 컬럼 저장 값:
     *   1      → 1분봉
     *   30     → 30분봉
     *   60     → 1시간봉
     *   1440   → 일봉
     *   43200  → 한달봉
     */
    public enum CandleType {
        MIN_1  (1,     "minutes", 1),
        MIN_30 (30,    "minutes", 30),
        HOUR_1 (60,    "minutes", 60),
        DAY    (1440,  "days",    -1),
        MONTH  (43200, "months",  -1);

        final int    dbUnit;   // DB unit 컬럼에 저장할 값
        final String apiType;  // 업비트 API 경로 ("minutes" / "days" / "months")
        final int    apiUnit;  // minutes일 때만 사용 (-1이면 무시)

        CandleType(int dbUnit, String apiType, int apiUnit) {
            this.dbUnit  = dbUnit;
            this.apiType = apiType;
            this.apiUnit = apiUnit;
        }
    }

    // =====================================================================
    //  PUBLIC API
    // =====================================================================

    /** 모든 캔들 타입 최신 데이터 업데이트 */
    public static void updateAll() {
        for (CandleType type : CandleType.values()) {
            updateData(type);
        }
    }

    /** 특정 캔들 타입 최신 데이터 업데이트 */
    public static void updateData(CandleType type) {
        System.out.println("\n=== [" + type.name() + "] 업데이트 시작 ===");
        for (String code : CoinConfig.getCodes()) {
            String market = "KRW-" + code;
            System.out.println("[" + market + "] 업데이트 확인 중...");
            updateCoinHistory(market, type);
        }
        System.out.println("=== [" + type.name() + "] 업데이트 완료 ===");
    }

    /** 모든 캔들 타입 6개월치 과거 데이터 수집 */
    public static void importAll() {
        for (CandleType type : CandleType.values()) {
            importData(type);
        }
    }

    /** 특정 캔들 타입 6개월치 과거 데이터 수집 */
    public static void importData(CandleType type) {
        LocalDateTime now        = LocalDateTime.now();
        LocalDateTime cutoffDate = now.minusMonths(6);

        System.out.println("\n=== [" + type.name() + "] 과거 데이터 수집 시작 ===");
        System.out.println("목표 기간: " + now.format(FORMATTER) + " ~ " + cutoffDate.format(FORMATTER));

        for (String code : CoinConfig.getCodes()) {
            String market = "KRW-" + code;
            System.out.println("[" + market + "] 수집 시작...");
            crawlCoinHistory(market, type, cutoffDate);
        }
        System.out.println("=== [" + type.name() + "] 수집 완료 ===");
    }

    // =====================================================================
    //  PRIVATE - UPDATE (최신 보충)
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

                        // 이미 저장된 데이터 구간에 도달하면 종료
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
                        totalSaved += batchCount;
                        System.out.print(".");
                    }

                    // 다음 페이지: 이번 배치의 가장 오래된 캔들을 toDate로 설정
                    JSONObject oldest = candles.getJSONObject(candles.length() - 1);
                    toDate = oldest.getString("candle_date_time_utc") + "Z";

                    Thread.sleep(300);

                } catch (Exception e) {
                    String errMsg = e.getMessage();
                    if (errMsg != null && errMsg.contains("429")) {
                        System.err.println("\n[RateLimit] " + market + " → 5초 대기 후 재시도...");
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    System.err.println("\n[Error] " + market + " 업데이트 중단: " + errMsg);
                    try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
                    break;
                }
            }

            conn.commit();
            System.out.println(" 완료 (새로 추가: " + totalSaved + "개)");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // =====================================================================
    //  PRIVATE - IMPORT (과거 수집)
    // =====================================================================

    private static void crawlCoinHistory(String market, CandleType type, LocalDateTime cutoffDate) {
        // 기존 데이터가 있으면 가장 오래된 시점부터 이어서 수집
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
                    totalSaved += candles.length();
                    System.out.print(".");

                    // 가장 오래된 캔들 확인 → 종료 조건 or 다음 페이지
                    JSONObject oldest = candles.getJSONObject(candles.length() - 1);
                    String     utcRaw = oldest.getString("candle_date_time_utc");
                    toDate = utcRaw + "Z";

                    if (parseUtc(utcRaw).isBefore(cutoffDate)) {
                        isFinished = true;
                    }

                    Thread.sleep(300);

                } catch (Exception e) {
                    String errMsg = e.getMessage();
                    if (errMsg != null && errMsg.contains("429")) {
                        System.err.println("\n[RateLimit] " + market + " → 5초 대기 후 재시도...");
                        try { Thread.sleep(5000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        continue;
                    }
                    System.err.println("\n[Error] " + market + " 수집 중단: " + errMsg);
                    try { conn.rollback(); } catch (SQLException ex) { ex.printStackTrace(); }
                    break;
                }
            }

            conn.commit();

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println(" 완료 (총 " + totalSaved + "개 추가)");
    }

    // =====================================================================
    //  PRIVATE - 업비트 API 호출
    // =====================================================================

    /**
     * CandleType에 따라 올바른 업비트 API 엔드포인트로 요청합니다.
     *   minutes → https://api.upbit.com/v1/candles/minutes/{unit}
     *   days    → https://api.upbit.com/v1/candles/days
     *   months  → https://api.upbit.com/v1/candles/months
     */
    private static JSONArray fetchCandles(String market, CandleType type,
                                          int count, String toDate) throws Exception {
        StringBuilder url = new StringBuilder("https://api.upbit.com/v1/candles/");
        url.append(type.apiType);

        // minutes 타입만 unit 경로 파라미터 추가
        if ("minutes".equals(type.apiType)) {
            url.append("/").append(type.apiUnit);
        }

        url.append("?market=").append(market)
           .append("&count=").append(count);

        if (toDate != null && !toDate.isEmpty()) {
            url.append("&to=").append(URLEncoder.encode(toDate, "UTF-8"));
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Accept", "application/json");

        int status = conn.getResponseCode();
        if (status != 200) {
            throw new RuntimeException("HTTP Error: " + status + " | URL: " + url);
        }

        BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder  sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);

        return new JSONArray(sb.toString());
    }

    // =====================================================================
    //  PRIVATE - DB 유틸
    // =====================================================================

    private static LocalDateTime getMaxDate(String market, int unit) {
        return queryDate(market, unit, "MAX");
    }

    private static LocalDateTime getMinDate(String market, int unit) {
        return queryDate(market, unit, "MIN");
    }

    /**
     * DB에서 해당 코인의 MAX/MIN 캔들 시간을 조회합니다.
     */
    private static LocalDateTime queryDate(String market, int unit, String func) {
        String sql = "SELECT " + func + "(candle_date_time_utc) FROM market_candle WHERE market = ? AND unit = ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, market);
            stmt.setInt(2, unit);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next() && rs.getTimestamp(1) != null) {
                    return rs.getTimestamp(1).toLocalDateTime();
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
               "ON DUPLICATE KEY UPDATE trade_price = VALUES(trade_price)";
    }

    private static void setPstmt(PreparedStatement pstmt, String market, int dbUnit,
                                  JSONObject c, String utcRaw) throws Exception {
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

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

        // JVM 종료 시 스케줄러 정상 종료
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[Scheduler] 종료 신호 수신 → 스케줄러 안전 종료 중...");
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(10, TimeUnit.SECONDS))
                    scheduler.shutdownNow();
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
            }
        }));

        // ① 1분봉  - 즉시 시작 → 60초(1분)마다 갱신
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateData(CandleType.MIN_1);
                System.out.println("[1분봉] 동기화 완료");
            } catch (Exception e) {
                System.err.println("[1분봉] 오류: " + e.getMessage());
            }
        }, 0, 60, TimeUnit.SECONDS);

        // ② 30분봉 - 10초 후 시작 → 1800초(30분)마다 갱신
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateData(CandleType.MIN_30);
                System.out.println("[30분봉] 동기화 완료");
            } catch (Exception e) {
                System.err.println("[30분봉] 오류: " + e.getMessage());
            }
        }, 10, 1800, TimeUnit.SECONDS);

        // ③ 1시간봉 - 20초 후 시작 → 3600초(1시간)마다 갱신
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateData(CandleType.HOUR_1);
                System.out.println("[1시간봉] 동기화 완료");
            } catch (Exception e) {
                System.err.println("[1시간봉] 오류: " + e.getMessage());
            }
        }, 20, 3600, TimeUnit.SECONDS);

        // ④ 일봉    - 30초 후 시작 → 86400초(1일)마다 갱신
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateData(CandleType.DAY);
                System.out.println("[일봉] 동기화 완료");
            } catch (Exception e) {
                System.err.println("[일봉] 오류: " + e.getMessage());
            }
        }, 30, 86400, TimeUnit.SECONDS);

        // ⑤ 한달봉  - 40초 후 시작 → 86400초(1일)마다 갱신
        scheduler.scheduleAtFixedRate(() -> {
            try {
                updateData(CandleType.MONTH);
                System.out.println("[한달봉] 동기화 완료");
            } catch (Exception e) {
                System.err.println("[한달봉] 오류: " + e.getMessage());
            }
        }, 40, 86400, TimeUnit.SECONDS);
    }
}