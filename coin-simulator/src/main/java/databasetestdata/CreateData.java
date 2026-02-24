package databasetestdata;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import io.github.cdimascio.dotenv.Dotenv;

public class CreateData {
	Dotenv dotenv = Dotenv.load();
	
    // TODO: 본인의 데이터베이스 환경에 맞게 URL, USER, PASSWORD를 수정해주세요.
    private  String DB_URL = dotenv.get("DB_URL");
    private  String DB_USER = dotenv.get("DB_USER");
    private  String DB_PASSWORD = dotenv.get("DB_PASSWORD");

    public CreateData() {
    }

    public static void main(String[] args) {
        CreateData createData = new CreateData();
        createData.insertDummyData();
    }

    public void insertDummyData() {
        try (Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
             Statement stmt = conn.createStatement()) {

            System.out.println("데이터베이스 연결 성공. 더미 데이터 삽입을 시작합니다...");

            // 1. 외래키 제약조건 일시 해제 (market_candle 더미데이터 생략을 위함)
            stmt.execute("SET FOREIGN_KEY_CHECKS = 0;");

            // 2. 기존 데이터 초기화 (원치 않으시면 주석 처리하세요)
            // stmt.execute("TRUNCATE TABLE executions;");
            // stmt.execute("TRUNCATE TABLE orders;");
            // stmt.execute("TRUNCATE TABLE assets;");
            // stmt.execute("TRUNCATE TABLE simulation_sessions;");
            // stmt.execute("TRUNCATE TABLE watchlists;");
            // stmt.execute("TRUNCATE TABLE price_alerts;");
            // stmt.execute("TRUNCATE TABLE users;");

            // 3. 더미 데이터 삽입 SQL
            String[] insertQueries = {
                // Users 더미 데이터
                "INSERT IGNORE INTO users (user_id, password, nickname, phone_number, auth_provider) VALUES " +
                "('test_user1', 'hashed_pw_1', '비트코인장인', '01012345678', 'EMAIL')," +
                "('test_user2', 'hashed_pw_2', '이더리움홀더', '01098765432', 'KAKAO')," +
                "('test_user3', 'hashed_pw_3', '단타의신', '01011112222', 'GOOGLE');",

                // Watchlists 더미 데이터 (market_candle에 데이터가 없어도 위에서 FK 검사를 껐으므로 삽입됨)
                "INSERT IGNORE INTO watchlists (user_id, market) VALUES " +
                "('test_user1', 'BTC')," +
                "('test_user1', 'ETH')," +
                "('test_user2', 'XRP');",

                // Simulation Sessions 더미 데이터
                "INSERT IGNORE INTO simulation_sessions (session_id, user_id, session_name, session_type, initial_seed_money) VALUES " +
                "(1, 'test_user1', '24년 반감기 실전연습', 'BACKTEST', 50000000)," +
                "(2, 'test_user2', '카카오 실시간 모의투자', 'REALTIME', 100000000);",

                // Assets 더미 데이터
                "INSERT IGNORE INTO assets (user_id, currency, session_id, balance, locked, avg_buy_price) VALUES " +
                "('test_user1', 'KRW', 1, 20000000, 0, 0)," +
                "('test_user1', 'BTC', 1, 0.5, 0.1, 60000000)," +
                "('test_user2', 'KRW', 2, 100000000, 5000000, 0)," +
                "('test_user2', 'ETH', 2, 10.0, 0, 3000000);",

                // Orders 더미 데이터 (생성된 ID는 executions에서 사용)
                "INSERT IGNORE INTO orders (order_id, user_id, session_id, market, side, type, original_price, original_volume, remaining_volume, status) VALUES " +
                "(1, 'test_user1', 1, 'KRW-BTC', 'BID', 'LIMIT', 60000000, 0.5, 0.0, 'DONE')," +
                "(2, 'test_user1', 1, 'KRW-BTC', 'ASK', 'LIMIT', 65000000, 0.1, 0.1, 'WAIT')," +
                "(3, 'test_user2', 2, 'KRW-ETH', 'BID', 'MARKET', 3000000, 10.0, 0.0, 'DONE');",

                // Executions 더미 데이터
                "INSERT IGNORE INTO executions (execution_id, order_id, user_id, market, side, price, volume, total_price, fee, buy_avg_price, realized_pnl, roi) VALUES " +
                "(1, 1, 'test_user1', 'KRW-BTC', 'BID', 60000000, 0.5, 30000000, 15000, 0, 0, 0.0)," +
                "(2, 3, 'test_user2', 'KRW-ETH', 'BID', 3000000, 10.0, 30000000, 15000, 0, 0, 0.0);",

                // Price Alerts 더미 데이터
                "INSERT IGNORE INTO price_alerts (user_id, market, target_price, condition_type, is_active) VALUES " +
                "('test_user1', 'BTC', 100000000, 'ABOVE', TRUE)," +
                "('test_user2', 'ETH', 2500000, 'BELOW', TRUE);"
            };

            for (String query : insertQueries) {
                stmt.execute(query);
            }

            // 4. 외래키 제약조건 원상 복구
            stmt.execute("SET FOREIGN_KEY_CHECKS = 1;");
            
            System.out.println("✅ 더미 데이터가 성공적으로 삽입되었습니다.");

        } catch (SQLException e) {
            System.err.println("❌ 데이터베이스 작업 중 오류가 발생했습니다: " + e.getMessage());
            e.printStackTrace();
        }
    }
}