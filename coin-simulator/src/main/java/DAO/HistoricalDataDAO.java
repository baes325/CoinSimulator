package DAO;

import com.team.coin_simulator.DBConnection;
import DTO.TickerDto;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 백테스팅용 과거 시세 데이터 조회 DAO
 */
public class HistoricalDataDAO {
    
    /**
     * 특정 시점의 모든 코인 현재가 조회 (메모리 초과 방지를 위해 최적화됨)
     */
    public Map<String, TickerDto> getTickersAtTime(LocalDateTime targetTime) {
        Map<String, TickerDto> tickers = new HashMap<>();
        
        // [핵심] 수백만 건을 가져오는 대신, 코인별로 targetTime 기준 가장 최신 1분봉 1개씩만 Join해서 가져옵니다.
        String sql = "SELECT m.market, m.trade_price, m.opening_price, " +
                     "       m.candle_acc_trade_price, m.candle_acc_trade_volume " +
                     "FROM market_candle m " +
                     "INNER JOIN (" +
                     "    SELECT market, MAX(candle_date_time_kst) AS max_time " +
                     "    FROM market_candle " +
                     "    WHERE candle_date_time_kst <= ? AND unit = 1 " +
                     "    GROUP BY market" +
                     ") latest ON m.market = latest.market AND m.candle_date_time_kst = latest.max_time " +
                     "WHERE m.unit = 1";
        
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setTimestamp(1, Timestamp.valueOf(targetTime));
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String market = rs.getString("market"); 
                    String symbol = market.replace("KRW-", ""); 
                    
                    TickerDto ticker = new TickerDto();
                    ticker.setCode(market);
                    ticker.setTrade_price(rs.getDouble("trade_price"));
                    
                    // 등락률 계산 (현재가 vs 시가)
                    double openPrice = rs.getDouble("opening_price");
                    if (openPrice > 0) {
                        double changeRate = (ticker.getTrade_price() - openPrice) / openPrice;
                        ticker.setSigned_change_rate(changeRate);
                    }
                    
                    ticker.setAcc_trade_price(rs.getDouble("candle_acc_trade_price"));
                    
                    tickers.put(symbol, ticker);
                }
            }
            
        } catch (SQLException e) {
            e.printStackTrace();
        }
        
        return tickers;
    }

    /**
     * [신규 추가] 백테스트 시작 시점 또는 날짜 변경 시 누적 거래대금 초기화용
     * 당일 오전 9시(KST)부터 targetTime까지의 합계를 조회합니다.
     */
    public Map<String, Double> getInitDailyAccTradePrice(LocalDateTime targetTime) {
        Map<String, Double> accVolumeMap = new HashMap<>();
        
        // 기준 시간: 오전 9시 (KST)
        LocalDateTime startOfDay = targetTime.withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (targetTime.isBefore(startOfDay)) {
            startOfDay = startOfDay.minusDays(1); // 만약 오전 9시 이전이면 전날 오전 9시로 세팅
        }

        String sql = "SELECT market, SUM(candle_acc_trade_price) as sum_price " +
                     "FROM market_candle " +
                     "WHERE candle_date_time_kst > ? AND candle_date_time_kst <= ? AND unit = 1 " +
                     "GROUP BY market";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setTimestamp(1, Timestamp.valueOf(startOfDay));
            pstmt.setTimestamp(2, Timestamp.valueOf(targetTime));
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String market = rs.getString("market").replace("KRW-", "");
                    accVolumeMap.put(market, rs.getDouble("sum_price"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return accVolumeMap;
    }
    
    //---------------------------------------------------------
    // 기존 getPriceAtTime, getCandlesUntil 메서드는 그대로 유지합니다.
    //---------------------------------------------------------
    public BigDecimal getPriceAtTime(String market, LocalDateTime targetTime) {
        String sql = "SELECT trade_price FROM market_candle " +
                    "WHERE market = ? AND candle_date_time_kst <= ? " +
                    "ORDER BY candle_date_time_kst DESC LIMIT 1";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, market);
            pstmt.setTimestamp(2, Timestamp.valueOf(targetTime));
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) return rs.getBigDecimal("trade_price");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return BigDecimal.ZERO;
    }
    
    
    /**
     * [신규 추가] 당일 오전 9시(또는 해당 일자의 가장 첫 거래) 시가를 가져옵니다.
     */
    public Map<String, Double> getDailyOpenPrices(LocalDateTime targetTime) {
        Map<String, Double> openPriceMap = new HashMap<>();
        
        // 기준 시간: 오전 9시 (KST)
        LocalDateTime startOfDay = targetTime.withHour(9).withMinute(0).withSecond(0).withNano(0);
        if (targetTime.isBefore(startOfDay)) {
            startOfDay = startOfDay.minusDays(1);
        }

        // 해당 일자(오전 9시 이후)의 코인별 가장 첫 번째 1분봉의 시가(opening_price)를 찾습니다.
        String sql = "SELECT m.market, m.opening_price " +
                     "FROM market_candle m " +
                     "INNER JOIN (" +
                     "    SELECT market, MIN(candle_date_time_kst) as first_time " +
                     "    FROM market_candle " +
                     "    WHERE candle_date_time_kst >= ? AND candle_date_time_kst <= ? AND unit = 1 " +
                     "    GROUP BY market" +
                     ") first_candle ON m.market = first_candle.market AND m.candle_date_time_kst = first_candle.first_time " +
                     "WHERE m.unit = 1";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            
            pstmt.setTimestamp(1, Timestamp.valueOf(startOfDay));
            pstmt.setTimestamp(2, Timestamp.valueOf(targetTime));
            
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String market = rs.getString("market").replace("KRW-", "");
                    openPriceMap.put(market, rs.getDouble("opening_price"));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return openPriceMap;
    }
    
    
    public java.util.List<DTO.MarketCandleDTO> getCandlesUntil(
            String market, int unit, LocalDateTime endTime, int count) {
        java.util.List<DTO.MarketCandleDTO> candles = new java.util.ArrayList<>();
        String sql = "SELECT * FROM market_candle " +
                    "WHERE market = ? AND unit = ? AND candle_date_time_kst <= ? " +
                    "ORDER BY candle_date_time_kst DESC LIMIT ?";
        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, market);
            pstmt.setInt(2, unit);
            pstmt.setTimestamp(3, Timestamp.valueOf(endTime));
            pstmt.setInt(4, count);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    DTO.MarketCandleDTO dto = new DTO.MarketCandleDTO();
                    dto.setMarket(rs.getString("market"));
                    dto.setUnit(rs.getInt("unit"));
                    dto.setCandleDateTimeKst(rs.getTimestamp("candle_date_time_kst"));
                    dto.setCandleDateTimeUtc(rs.getTimestamp("candle_date_time_utc"));
                    dto.setOpeningPrice(rs.getBigDecimal("opening_price"));
                    dto.setHighPrice(rs.getBigDecimal("high_price"));
                    dto.setLowPrice(rs.getBigDecimal("low_price"));
                    dto.setTradePrice(rs.getBigDecimal("trade_price"));
                    dto.setTimestamp(rs.getLong("timestamp"));
                    dto.setCandleAccTradePrice(rs.getBigDecimal("candle_acc_trade_price"));
                    dto.setCandleAccTradeVolume(rs.getBigDecimal("candle_acc_trade_volume"));
                    candles.add(dto);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return candles;
    }
}