package com.team.coin_simulator.chart;

import com.team.coin_simulator.DBConnection;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class CandleDAO {

    // 1. 대량의 캔들 데이터 저장 (Batch Insert)
    // 업비트 API 등에서 받아온 많은 데이터를 한 번에 넣을 때 사용합니다.
    public void insertCandles(List<CandleDTO> candleList) {
        String sql = "INSERT INTO candles (market, candle_date_time_kst, candle_date_time_utc, " +
                     "opening_price, high_price, low_price, trade_price, timestamp, " +
                     "candle_acc_trade_price, candle_acc_trade_volume, unit) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            conn.setAutoCommit(false); // 트랜잭션 수동 제어 (성능 향상)

            for (CandleDTO candle : candleList) {
                pstmt.setString(1, candle.getMarket());
                pstmt.setTimestamp(2, Timestamp.valueOf(candle.getCandleDateTimeKst()));
                pstmt.setTimestamp(3, Timestamp.valueOf(candle.getCandleDateTimeUtc()));
                pstmt.setDouble(4, candle.getOpeningPrice());
                pstmt.setDouble(5, candle.getHighPrice());
                pstmt.setDouble(6, candle.getLowPrice());
                pstmt.setDouble(7, candle.getTradePrice());
                pstmt.setLong(8, candle.getTimestamp());
                pstmt.setDouble(9, candle.getCandleAccTradePrice());
                pstmt.setDouble(10, candle.getCandleAccTradeVolume());
                pstmt.setInt(11, candle.getUnit());
                
                pstmt.addBatch(); // 배치에 추가
            }

            pstmt.executeBatch(); // 일괄 실행
            conn.commit();        // 성공 시 커밋
            
        } catch (SQLException e) {
            e.printStackTrace();
            // 실패 시 롤백 로직 필요 (선택사항)
        }
    }

    // 2. 특정 종목의 캔들 데이터 조회 (최신순)
    public List<CandleDTO> getCandles(String market, int unit, int limit) {
        List<CandleDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM market_candle WHERE market = ? AND unit = ? " +
                     "ORDER BY candle_date_time_kst DESC LIMIT ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, market);
            pstmt.setInt(2, unit);
            pstmt.setInt(3, limit);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CandleDTO dto = new CandleDTO();
                    dto.setMarket(rs.getString("market"));
                    dto.setCandleDateTimeKst(rs.getTimestamp("candle_date_time_kst").toLocalDateTime());
                    dto.setCandleDateTimeUtc(rs.getTimestamp("candle_date_time_utc").toLocalDateTime());
                    dto.setOpeningPrice(rs.getDouble("opening_price"));
                    dto.setHighPrice(rs.getDouble("high_price"));
                    dto.setLowPrice(rs.getDouble("low_price"));
                    dto.setTradePrice(rs.getDouble("trade_price"));
                    dto.setTimestamp(rs.getLong("timestamp"));
                    dto.setCandleAccTradePrice(rs.getDouble("candle_acc_trade_price"));
                    dto.setCandleAccTradeVolume(rs.getDouble("candle_acc_trade_volume"));
                    dto.setUnit(rs.getInt("unit"));
                    list.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
    
    // 특정 시점(targetTime) 이전의 데이터를 가져오는 메서드
    public List<CandleDTO> getHistoricalCandles(String market, int unit, java.time.LocalDateTime targetTime, int limit) {
        List<CandleDTO> list = new ArrayList<>();
        // market_candle 테이블에서 targetTime보다 작거나 같은(과거) 데이터만 최신순으로 조회
        String sql = "SELECT * FROM market_candle WHERE market = ? AND unit = ? " +
                     "AND candle_date_time_kst <= ? " + 
                     "ORDER BY candle_date_time_kst DESC LIMIT ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setString(1, market);
            pstmt.setInt(2, unit);
            pstmt.setTimestamp(3, java.sql.Timestamp.valueOf(targetTime));
            pstmt.setInt(4, limit);
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    CandleDTO dto = new CandleDTO();
                    dto.setMarket(rs.getString("market"));
                    dto.setCandleDateTimeKst(rs.getTimestamp("candle_date_time_kst").toLocalDateTime());
                    dto.setCandleDateTimeUtc(rs.getTimestamp("candle_date_time_utc").toLocalDateTime());
                    dto.setOpeningPrice(rs.getDouble("opening_price"));
                    dto.setHighPrice(rs.getDouble("high_price"));
                    dto.setLowPrice(rs.getDouble("low_price"));
                    dto.setTradePrice(rs.getDouble("trade_price"));
                    dto.setTimestamp(rs.getLong("timestamp"));
                    dto.setCandleAccTradePrice(rs.getDouble("candle_acc_trade_price"));
                    dto.setCandleAccTradeVolume(rs.getDouble("candle_acc_trade_volume"));
                    dto.setUnit(rs.getInt("unit"));
                    list.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }
}