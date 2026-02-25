package com.team.coin_simulator;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import io.github.cdimascio.dotenv.Dotenv;

public class DBConnection {

    private static HikariDataSource dataSource;

    // 🔥 풀 생성 로직 분리
    private static void initPool() {
        try {
            HikariConfig config = new HikariConfig();

            Dotenv dotenv = Dotenv.load();

            config.setJdbcUrl(dotenv.get("DB_URL"));
            config.setUsername(dotenv.get("DB_USER"));
            config.setPassword(dotenv.get("DB_PASSWORD"));
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            config.setMaximumPoolSize(10);
            config.setMinimumIdle(2);
            config.setIdleTimeout(30000);
            config.setConnectionTimeout(30000);

            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

            dataSource = new HikariDataSource(config);

            System.out.println("[DBConnection] HikariPool CREATED");

        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("DB Connection Pool 초기화 실패", e);
        }
    }

    static {
        initPool();
    }

    private DBConnection() {}

    // ✅ 핵심 수정: 닫혀있으면 자동 재생성
    public static Connection getConnection() throws SQLException {

        if (dataSource == null || dataSource.isClosed()) {
            System.out.println("[DBConnection] Pool was closed -> Recreating...");
            initPool();
        }

        return dataSource.getConnection();
    }

    // ⚠️ 프로그램 종료시에만 사용
    public static void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            System.out.println("[DBConnection] Pool CLOSED");
        }
    }
}