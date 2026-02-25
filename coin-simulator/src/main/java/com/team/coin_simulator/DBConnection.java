package com.team.coin_simulator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import io.github.cdimascio.dotenv.Dotenv;

public class DBConnection {

    private static HikariDataSource dataSource;

    // 정적 초기화 블록 (클래스 로드 시 1회 실행)
    static {
        try {
            HikariConfig config = new HikariConfig();
            
            // 1. 필수 설정
            Dotenv dotenv = Dotenv.load();

	         // 2. config 설정에 환경 변수 적용하기
	        config.setJdbcUrl(dotenv.get("DB_URL"));
	        config.setUsername(dotenv.get("DB_USER"));
	        config.setPassword(dotenv.get("DB_PASSWORD"));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            // 2. 성능 및 풀 옵션 (데스크탑/시뮬레이터 환경 최적화)
            config.setMaximumPoolSize(10);      // 최대 커넥션 수 (스윙 앱은 10개면 충분)
            config.setMinimumIdle(2);           // 유휴 커넥션 최소 유지 수
            config.setIdleTimeout(30000);       // 유휴 커넥션 생존 시간 (30초)
            config.setConnectionTimeout(30000); // 커넥션 획득 대기 시간 (30초)
            
            // 3. 캐싱 옵션 (성능 향상)
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            dataSource = new HikariDataSource(config);
            
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("DB Connection Pool 초기화 실패", e);
        }
    }

    private DBConnection() {} // 인스턴스 생성 방지

    // 커넥션 획득
    public static Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    // 리소스 해제 (애플리케이션 종료 시 호출)
    public static void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}