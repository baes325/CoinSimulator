package com.team.coin_simulator.chart;

import com.team.coin_simulator.DBConnection;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class CandleDAO {

    // ════════════════════════════════════════════════════
    //  공통 매핑 헬퍼
    // ════════════════════════════════════════════════════

    private CandleDTO mapRow(ResultSet rs) throws SQLException {
        CandleDTO dto = new CandleDTO();
        dto.setMarket(rs.getString("market"));
        dto.setCandleDateTimeKst(rs.getObject("candle_date_time_kst", LocalDateTime.class));
        dto.setCandleDateTimeUtc(rs.getObject("candle_date_time_utc", LocalDateTime.class));
        dto.setOpeningPrice(rs.getDouble("opening_price"));
        dto.setHighPrice(rs.getDouble("high_price"));
        dto.setLowPrice(rs.getDouble("low_price"));
        dto.setTradePrice(rs.getDouble("trade_price"));
        dto.setTimestamp(rs.getLong("timestamp"));
        dto.setCandleAccTradePrice(rs.getDouble("candle_acc_trade_price"));
        dto.setCandleAccTradeVolume(rs.getDouble("candle_acc_trade_volume"));
        dto.setUnit(rs.getInt("unit"));
        return dto;
    }

    // ════════════════════════════════════════════════════
    //  기존 메서드
    // ════════════════════════════════════════════════════

    /**
     * 특정 종목의 캔들 데이터 조회 (최신순 LIMIT)
     */
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
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * 특정 시점(targetTime) 이전의 데이터를 가져오는 메서드 (백테스팅용)
     */
    public List<CandleDTO> getHistoricalCandles(String market, int unit,
                                                 LocalDateTime targetTime, int limit) {
        List<CandleDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM market_candle WHERE market = ? AND unit = ? " +
                     "AND candle_date_time_kst <= ? " +
                     "ORDER BY candle_date_time_kst DESC LIMIT ?";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, market);
            pstmt.setInt(2, unit);
            pstmt.setTimestamp(3, Timestamp.valueOf(targetTime));
            pstmt.setInt(4, limit);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    // ════════════════════════════════════════════════════
    //  ★ 신규: 뷰포트 페이징용 시간 범위 조회
    // ════════════════════════════════════════════════════

    /**
     * [뷰포트 페이징] from ~ to 시간 범위의 캔들을 ASC 순으로 조회합니다.
     *
     * 30만 개의 1분봉 데이터에서 현재 화면에 보이는 구간 + 버퍼만 가져올 때 사용합니다.
     * DESC LIMIT 방식과 달리 시간 범위를 직접 지정하므로, 어느 구간으로 드래그해도
     * 해당 구간의 데이터만 DB에서 효율적으로 읽어옵니다.
     *
     * @param market 종목 코드 (예: "KRW-BTC")
     * @param unit   타임프레임 분 단위 (예: 1 = 1분봉)
     * @param from   조회 시작 시각 (포함)
     * @param to     조회 종료 시각 (포함)
     * @return ASC 정렬된 CandleDTO 리스트
     */
    public List<CandleDTO> getCandlesInRange(String market, int unit,
                                              LocalDateTime from, LocalDateTime to) {
        List<CandleDTO> list = new ArrayList<>();
        String sql = "SELECT * FROM market_candle " +
                     "WHERE market = ? AND unit = ? " +
                     "AND candle_date_time_utc >= ? AND candle_date_time_utc <= ? " +
                     "ORDER BY candle_date_time_utc ASC";

        try (Connection conn = DBConnection.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, market);
            pstmt.setInt(2, unit);
            pstmt.setObject(3, Timestamp.valueOf(from));
            pstmt.setObject(4, Timestamp.valueOf(to));

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    list.add(mapRow(rs));
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return list;
    }

    /**
     * [뷰포트 페이징 + 백테스팅] from ~ min(to, targetTime) 범위 조회.
     *
     * 백테스팅 모드에서 드래그/줌 시 targetTime을 초과하는 미래 데이터가
     * 보이지 않도록 상한을 targetTime으로 클램핑합니다.
     *
     * @param market      종목 코드
     * @param unit        타임프레임 분 단위
     * @param from        조회 시작 시각
     * @param to          조회 종료 시각 (targetTime으로 클램핑됨)
     * @param targetTime  백테스팅 기준 시각
     * @return ASC 정렬된 CandleDTO 리스트
     */
    public List<CandleDTO> getCandlesInRangeHistorical(String market, int unit,
                                                        LocalDateTime from, LocalDateTime to,
                                                        LocalDateTime targetTime) {
        // targetTime이 to보다 앞이면 to를 targetTime으로 클램핑
        LocalDateTime clampedTo = to.isAfter(targetTime) ? targetTime : to;
        return getCandlesInRange(market, unit, from, clampedTo);
    }
}