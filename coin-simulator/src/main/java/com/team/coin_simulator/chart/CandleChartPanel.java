package com.team.coin_simulator.chart;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import javax.swing.*;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;

import org.jfree.chart.*;
import org.jfree.chart.axis.*;
import org.jfree.chart.plot.ValueMarker;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.CandlestickRenderer;
import org.jfree.data.xy.DefaultHighLowDataset;
import org.jfree.data.xy.OHLCDataset;

/**
 * [CandleChartPanel]
 * JFreeChart를 이용한 암호화폐 캔들 차트 컴포넌트.
 *
 * ── 주요 기능 ──────────────────────────────────────────
 *  1. 현재가 점선 + Y축 영역 위 가격 박스 표시
 *  2. 초기 로딩 시 최근 N개 캔들만 표시, 가격 범위 자동 맞춤
 *  3. 실시간 라이브 캔들 업데이트 (Swing Timer 기반)
 *     - DB에서 1분봉을 가져와 표시
 *     - 매 분 00초에 라이브 캔들을 DB 데이터로 대체하고 새 분봉 시작
 *  4. 백테스팅 모드: targetTime 기준 과거 데이터만 표시
 *     ★ 타임프레임 변경 시에도 백테스팅 시점 유지
 *  5. DB에서 타임프레임(unit)에 맞는 캔들을 직접 조회 (리샘플링 제거)
 *     - 지원 타임프레임: 1분 / 30분 / 1시간 / 4시간 / 1일 / 1달
 *     - market_candle 테이블에 unit 컬럼별로 사전 집계된 데이터 필요
 * ────────────────────────────────────────────────────────
 */
public class CandleChartPanel extends JPanel {

    // ── 타임프레임 정의 (분 단위) ────────────────────────
    private static final int TF_1M   = 1;
    private static final int TF_30M  = 30;
    private static final int TF_1H   = 60;
    private static final int TF_4H   = 240;
    private static final int TF_1D   = 1440;
    private static final int TF_1MON = 43200; // 30일 기준

    /** 타임프레임별 화면 기본 표시 캔들 수 */
    private static final int DISPLAY_1M   = 55;
    private static final int DISPLAY_30M  = 55;
    private static final int DISPLAY_1H   = 55;
    private static final int DISPLAY_4H   = 55;
    private static final int DISPLAY_1D   = 55;
    private static final int DISPLAY_1MON = 55;

    
     //각 타임프레임별로 생성할 최대 캔들 개수
    private static final int MAX_CANDLE_COUNT = 5000;

    // ── 차트 컴포넌트 ────────────────────────────────────
    private JFreeChart chart;
    private XYPlot plot;
    private OverlayChartPanel chartPanel;
    private CandleDAO candleDAO = new CandleDAO();
    private JButton selectedButton;

    // ── 초기 상태 ────────────────────────────────────────
    private String currentMarket = "KRW-BTC"; //기본 비트코인
    private int currentTimeframe = TF_1M; // 기본 1분봉
    private Point lastMousePoint;

    // ── 초기 백테스팅 상태 ─────────────────────────────────────
    private LocalDateTime backtestTargetTime = null;

    // ── 실시간 라이브 캔들 ────────────────────────────────
    /**
     * 현재 진행 중인 라이브(미확정) 캔들.
     * 매 분 00초가 되면 DB에서 확정된 1분봉으로 교체되고,
     * liveCandle은 새로운 분봉 데이터로 초기화됩니다.
     */
    private CandleDTO liveCandle = null;

    /**
     * liveCandle이 속하는 분봉 구간의 시작 시각 (초·나노초 = 0).
     * ex) 14:23:37 → 14:23:00
     */
    private LocalDateTime liveCandleMinuteStart = null;

    // ── 실시간 타이머 ────────────────────────────────────
    private Timer liveTimer;
    private static final int LIVE_RENDER_MS = 500; // 500ms마다 화면 갱신
    private volatile double latestLivePrice = -1;
    private volatile long latestLiveTimestamp = -1; //거래소 서버의 타임스탬프 저장용

    // ── 웹소켓 ───────────────────────────────────────────
    private UpbitWebSocket webSocketClient;

    // ── 현재가 박스 표시 ──────────────────────────────────
    private double overlayPrice = Double.NaN;
    private boolean overlayRising = true;


    // ════════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════════
    public CandleChartPanel(String title) {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, null);
        chart = ChartFactory.createCandlestickChart(null, "", "", dataset, false);
        chart.setTitle(getCurrentMarketSymbol());
        plot = (XYPlot) chart.getPlot();

        configureChartUI();
        setupChartPanel();
        createButtonPanel();

        add(chartPanel, BorderLayout.CENTER);

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        drawCurrentPriceDashLine(dataset);

        startLiveTimer();
        connectWebSocket();
    }


    // ════════════════════════════════════════════════════
    //  차트 UI 설정
    // ════════════════════════════════════════════════════

    private void configureChartUI() {
        plot.setRangeAxisLocation(AxisLocation.TOP_OR_RIGHT);
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setAutoRangeIncludesZero(false);

        //과학적 표기법(9.15E-3) 대신 일반 소수점(0.00915)
        yAxis.setNumberFormatOverride(buildPriceFormat());

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM/dd HH:mm"));
        domainAxis.setTickLabelFont(new Font("Nanum Gothic", Font.PLAIN, 11));
        domainAxis.setAutoTickUnitSelection(true);

        CandlestickRenderer renderer = new CandlestickRenderer();
        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST);
        renderer.setAutoWidthGap(0.0);   // 간격 없음 — zoom 시 벌어지는 현상 제거
        renderer.setDrawVolume(false);
        renderer.setUpPaint(Color.RED);
        renderer.setDownPaint(Color.BLUE);
        plot.setRenderer(renderer);
    }

    private void setupChartPanel() {
        chartPanel = new OverlayChartPanel(chart);
        chartPanel.setPreferredSize(new Dimension(900, 500));
        chartPanel.setRangeZoomable(false);
        chartPanel.setDomainZoomable(false);
        chartPanel.setMouseWheelEnabled(false);
        plot.setDomainPannable(true);
        plot.setRangePannable(true);

        chartPanel.addMouseWheelListener(e -> {
            handleFixedRightZoom(e.getWheelRotation());
            SwingUtilities.invokeLater(() -> chartPanel.repaint());
        });

        chartPanel.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e)  { lastMousePoint = e.getPoint(); }
            public void mouseReleased(MouseEvent e) { lastMousePoint = null; chartPanel.repaint(); }
        });

        chartPanel.addMouseMotionListener(new MouseAdapter() {
            public void mouseDragged(MouseEvent e) {
                if (lastMousePoint == null) return;

                int dx = e.getX() - lastMousePoint.x;
                lastMousePoint = e.getPoint();

                DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
                Rectangle2D plotArea = chartPanel.getScreenDataArea();
                if (plotArea == null || plotArea.getWidth() == 0) return;

                double msPerPixel = domainAxis.getRange().getLength() / plotArea.getWidth();
                double shift = -dx * msPerPixel;

                domainAxis.setRange(
                    domainAxis.getRange().getLowerBound() + shift,
                    domainAxis.getRange().getUpperBound() + shift
                );

                updateYAxisRange((OHLCDataset) plot.getDataset());
                chartPanel.repaint();
            }
        });
    }

    private void createButtonPanel() {
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        buttonPanel.setBackground(Color.WHITE);

        String[] labels     = {"1분",  "30분",  "1시간", "4시간", "1일",   "1달"};
        int[]    timeframes = {TF_1M, TF_30M, TF_1H,  TF_4H,  TF_1D, TF_1MON};

        for (int i = 0; i < labels.length; i++) {
            final String label     = labels[i];
            final int    timeframe = timeframes[i];

            JButton button = new JButton(label);
            button.setBackground(Color.LIGHT_GRAY);
            button.setFocusPainted(false);

            if (timeframe == currentTimeframe) {
                button.setBackground(Color.ORANGE);
                selectedButton = button;
            }

            button.addActionListener(e -> {
                if (selectedButton != null) selectedButton.setBackground(Color.LIGHT_GRAY);
                button.setBackground(Color.ORANGE);
                selectedButton = button;
                currentTimeframe = timeframe;

                // ★ refreshChart()가 backtestTargetTime을 참조하므로
                //   백테스팅 시점이 자동으로 유지됩니다.
                refreshChart();
            });

            buttonPanel.add(button);
        }
        add(buttonPanel, BorderLayout.NORTH);
    }


    // ════════════════════════════════════════════════════
    //  통합 데이터셋 생성
    // ════════════════════════════════════════════════════

    /**
     * 실시간/백테스팅 모드에 따라 해당 타임프레임(unit) 캔들을 직접 조회합니다.
     * DB의 market_candle 테이블에 unit 컬럼별로 사전 집계된 데이터가 있어야 합니다.
     */
    private OHLCDataset createDataset(String market, int timeframe, LocalDateTime targetTime) {
        List<CandleDTO> list;
        
        // unit=240이 DB에 없으므로 4시간봉은 1시간봉(unit=60)을 4개씩 묶어 리샘플링
        if (timeframe == TF_4H) {
            int fetchLimit = (TF_4H / TF_1H) * MAX_CANDLE_COUNT; // 1시간봉 4배 조회
            if (targetTime != null) {
                list = candleDAO.getHistoricalCandles(market, TF_1H, targetTime, fetchLimit);
            } else {
                list = candleDAO.getCandles(market, TF_1H, fetchLimit);
            }
            if (list.isEmpty()) return emptyDataset(market);
            Collections.reverse(list);
            return buildDataset(market, resample4H(list));
        }

        if (targetTime != null) {
            list = candleDAO.getHistoricalCandles(market, timeframe, targetTime, MAX_CANDLE_COUNT);
        } else {
            list = candleDAO.getCandles(market, timeframe, MAX_CANDLE_COUNT);
        }

        if (list.isEmpty()) return emptyDataset(market);

        // DB 조회는 DESC → ASC로 뒤집기
        Collections.reverse(list);

        return buildDataset(market, list);
    }

    /**
     * 실시간 모드 전용: DB의 확정된 1분봉 데이터 + 현재 진행 중인 liveCandle을 합쳐 데이터셋 생성.
     *
     * ── 동작 원리 ──────────────────────────────────────────────────
     *  1) DB에서 최신 1분봉 목록을 가져옴 (liveCandle이 속하는 현재 분봉 제외)
     *  2) 리샘플링 후 마지막에 liveCandle을 덧붙임
     *     - 현재 타임프레임이 1분봉이면 liveCandle을 그냥 추가
     *     - 상위 타임프레임이면 마지막 리샘플 캔들에 liveCandle을 병합
     * ────────────────────────────────────────────────────────────────
     */
    /**
     * 실시간 모드 전용: DB의 확정된 캔들 데이터(해당 타임프레임) + 현재 진행 중인 liveCandle을 합쳐 데이터셋 생성.
     *
     * ── 동작 원리 ──────────────────────────────────────────────────
     *  1) DB에서 해당 타임프레임(unit)의 캔들을 직접 조회 (리샘플링 불필요)
     *  2) liveCandle(1분 단위 실시간 캔들)을 마지막에 덧붙임
     *     - 1분봉: liveCandle을 그냥 추가 (같은 분봉이면 대체)
     *     - 상위 타임프레임: liveCandle이 마지막 DB 캔들과 같은 블록이면 병합,
     *       아니면 새 블록으로 추가
     * ────────────────────────────────────────────────────────────────
     */
    private OHLCDataset createDatasetWithLiveCandle(String market, int timeframe) {
    	// unit=240이 DB에 없으므로 4시간봉은 1시간봉(unit=60)으로 리샘플링
        if (timeframe == TF_4H) {
            int fetchLimit = (TF_4H / TF_1H) * MAX_CANDLE_COUNT;
            List<CandleDTO> rawList = candleDAO.getCandles(market, TF_1H, fetchLimit);
            Collections.reverse(rawList);

            if (liveCandle == null) {
                if (rawList.isEmpty()) return emptyDataset(market);
                return buildDataset(market, resample4H(rawList));
            }

            // liveCandle(1분봉)을 1시간봉 rawList에 붙여서 함께 리샘플링
            // 중복 방지: rawList 마지막과 liveCandle이 같은 시간이면 제거
            if (!rawList.isEmpty() && liveCandleMinuteStart != null) {
                CandleDTO last = rawList.get(rawList.size() - 1);
                LocalDateTime lastHour = last.getCandleDateTimeKst()
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
                LocalDateTime liveHour = liveCandle.getCandleDateTimeKst()
                        .truncatedTo(java.time.temporal.ChronoUnit.HOURS);
                if (lastHour.equals(liveHour)) {
                    rawList.remove(rawList.size() - 1);
                }
            }
            rawList.add(liveCandle);
            return buildDataset(market, resample4H(rawList));
        }
    	
        // 해당 타임프레임 단위로 DB에서 직접 조회
        List<CandleDTO> list = candleDAO.getCandles(market, timeframe, MAX_CANDLE_COUNT);
        Collections.reverse(list); // DESC → ASC

        // liveCandle이 없으면 DB 데이터만으로 렌더링
        if (liveCandle == null) {
            if (list.isEmpty()) return emptyDataset(market);
            return buildDataset(market, list);
        }

        if (timeframe == TF_1M) {
            // ── 1분봉 ──
            // DB 마지막 캔들이 liveCandle과 같은 분이면 제거 후 liveCandle로 대체
            if (!list.isEmpty() && liveCandleMinuteStart != null) {
                CandleDTO last = list.get(list.size() - 1);
                LocalDateTime lastMinute = last.getCandleDateTimeKst()
                        .truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
                if (lastMinute.equals(liveCandleMinuteStart)) {
                    list.remove(list.size() - 1);
                }
            }
            list.add(liveCandle);
        } else {
            // ── 상위 타임프레임 ──
            // liveCandle(1분봉)이 DB 마지막 캔들과 같은 타임프레임 블록이면 종가/고가/저가 병합
            if (!list.isEmpty()) {
                CandleDTO last = list.get(list.size() - 1);
                String lastBlock = calcBlockKey(last.getCandleDateTimeKst(), timeframe);
                String liveBlock = calcBlockKey(liveCandle.getCandleDateTimeKst(), timeframe);

                if (lastBlock.equals(liveBlock)) {
                    // 같은 블록: DB 마지막 캔들의 종가/고/저를 실시간 가격으로 갱신
                    // (원본 객체를 직접 수정하지 않고 복사본 사용)
                    CandleDTO merged = copyCandle(last);
                    merged.setTradePrice(liveCandle.getTradePrice());
                    merged.setHighPrice(Math.max(last.getHighPrice(), liveCandle.getHighPrice()));
                    merged.setLowPrice(Math.min(last.getLowPrice(), liveCandle.getLowPrice()));
                    list.set(list.size() - 1, merged);
                } else {
                    // 새 블록 시작: liveCandle을 해당 타임프레임 블록의 시작 캔들로 추가
                    CandleDTO newBlock = copyCandle(liveCandle);
                    newBlock.setUnit(timeframe);
                    list.add(newBlock);
                }
            } else {
                list.add(liveCandle);
            }
        }

        return buildDataset(market, list);
    }

    /** CandleDTO 얕은 복사 (DB 원본 객체 보호용) */
    private CandleDTO copyCandle(CandleDTO src) {
        CandleDTO c = new CandleDTO();
        c.setMarket(src.getMarket());
        c.setCandleDateTimeKst(src.getCandleDateTimeKst());
        c.setCandleDateTimeUtc(src.getCandleDateTimeUtc());
        c.setOpeningPrice(src.getOpeningPrice());
        c.setHighPrice(src.getHighPrice());
        c.setLowPrice(src.getLowPrice());
        c.setTradePrice(src.getTradePrice());
        c.setTimestamp(src.getTimestamp());
        c.setCandleAccTradePrice(src.getCandleAccTradePrice());
        c.setCandleAccTradeVolume(src.getCandleAccTradeVolume());
        c.setUnit(src.getUnit());
        return c;
    }

    private OHLCDataset emptyDataset(String market) {
        return new DefaultHighLowDataset(market,
                new Date[0], new double[0], new double[0],
                new double[0], new double[0], new double[0]);
    }

    /**
     * 주어진 시각이 속하는 타임프레임 블록 키를 반환합니다.
     * liveCandle을 상위 타임프레임 블록에 병합할 때 사용됩니다.
     */
    private String calcBlockKey(LocalDateTime kst, int timeframeMinutes) {
        if (timeframeMinutes == TF_1MON) {
            return kst.getYear() + "-" + String.format("%02d", kst.getMonthValue());
        }
        // KST(UTC+9) 기준으로 블록 경계를 맞추기 위해 기준점을 1970-01-01 09:00(KST 자정)으로 설정
        // UTC 00:00 기준으로 계산하면 KST 데이터의 블록 경계가 9시간 어긋나 캔들이 틀린 위치에 그려짐
        long epochMinutes = ChronoUnit.MINUTES.between(LocalDateTime.of(1970, 1, 1, 9, 0), kst);
        return Long.toString(epochMinutes / timeframeMinutes);
    }


    // ════════════════════════════════════════════════════
    //  차트 갱신
    // ════════════════════════════════════════════════════

    private void refreshChart() {
        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, backtestTargetTime);
        plot.setDataset(dataset);

        chart.setTitle(backtestTargetTime != null
                ? getCurrentMarketSymbol() + " (Backtesting)"
                : getCurrentMarketSymbol());

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        drawCurrentPriceDashLine(dataset);
        applyDateAxisFormat();
    }

    /** 타임프레임에 맞는 X축 날짜 포맷 적용 */
    private void applyDateAxisFormat() {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        String fmt;
        if (currentTimeframe >= TF_1MON)   fmt = "yyyy/MM";
        else if (currentTimeframe >= TF_1D) fmt = "MM/dd";
        else                                fmt = "MM/dd HH:mm";
        domainAxis.setDateFormatOverride(new SimpleDateFormat(fmt));
    }

    /**
     * X축 범위를 최신 displayCount개 캔들이 보이도록 설정합니다.
     *
     * ── zoom 후 캔들 간격이 벌어지는 문제 해결 ──────────────────
     * 기존 코드는 하나의 고정된 oneBarInterval을 사용했기 때문에
     * zoom 후 범위가 달라지면 캔들 간격 계산이 어긋났습니다.
     * 수정: 타임프레임에서 직접 밀리초 단위 캔들 간격을 계산합니다.
     */
    private void updateXAxisRange(OHLCDataset dataset) {
        int itemCount = dataset.getItemCount(0);
        if (itemCount <= 0) return;

        int displayCount = Math.min(itemCount, getDisplayCount(currentTimeframe));

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double lastTick  = dataset.getXValue(0, itemCount - 1);
        double firstTick = dataset.getXValue(0, itemCount - displayCount);

        // 타임프레임에서 정확한 캔들 간격(ms) 산출
        double candleIntervalMs = getCandleIntervalMs(currentTimeframe);

        // 오른쪽에 빈 공간(라이브 캔들 위한 여유) + 왼쪽에 반 칸 여유
        domainAxis.setRange(
            firstTick  - candleIntervalMs * 0.5,
            lastTick   + candleIntervalMs * 4.5
        );
    }

    /**
     * 타임프레임(분)을 밀리초로 변환합니다.
     * 1달봉은 30일 * 24h * 60m 기준.
     */
    //여기 계속 고치네;;
    private double getCandleIntervalMs(int timeframeMinutes) { 
        long minutes = (timeframeMinutes == TF_1MON) ? 30L * 24 * 60 : timeframeMinutes;
        return minutes * 60_000.0;
    }

    private int getDisplayCount(int tf) {
        if (tf == TF_1M)   return DISPLAY_1M;
        if (tf == TF_30M)  return DISPLAY_30M;
        if (tf == TF_1H)   return DISPLAY_1H;
        if (tf == TF_4H)   return DISPLAY_4H;
        if (tf == TF_1D)   return DISPLAY_1D;
        if (tf == TF_1MON) return DISPLAY_1MON;
        return 55;
    }

    private void updateYAxisRange(OHLCDataset dataset) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double visibleStart = domainAxis.getRange().getLowerBound();
        double visibleEnd   = domainAxis.getRange().getUpperBound();

        double maxHigh = -Double.MAX_VALUE;
        double minLow  =  Double.MAX_VALUE;
        boolean found  = false;

        for (int i = 0; i < dataset.getItemCount(0); i++) {
            double x = dataset.getXValue(0, i);
            if (x >= visibleStart && x <= visibleEnd) {
                maxHigh = Math.max(maxHigh, dataset.getHighValue(0, i));
                minLow  = Math.min(minLow,  dataset.getLowValue(0, i));
                found   = true;
            }
        }

        if (found) {
            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double margin = (maxHigh - minLow) * 0.05;
            yAxis.setRange(minLow - margin, maxHigh + margin);
            // 범위가 바뀌어도 소수점 포맷 유지
            yAxis.setNumberFormatOverride(buildPriceFormat());
        }
    }

    private void drawCurrentPriceDashLine(OHLCDataset dataset) {
        plot.clearRangeMarkers();
        int itemCount = dataset.getItemCount(0);
        if (itemCount <= 0) return;

        double currentPrice  = dataset.getCloseValue(0, itemCount - 1);
        double previousPrice = (itemCount > 1) ? dataset.getCloseValue(0, itemCount - 2) : currentPrice;
        overlayRising = (currentPrice >= previousPrice);
        overlayPrice  = currentPrice;

        Color color = overlayRising ? Color.RED : Color.BLUE;
        ValueMarker marker = new ValueMarker(currentPrice);
        marker.setPaint(color);
        marker.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER,
                10.0f, new float[]{5.0f}, 0.0f));
        plot.addRangeMarker(marker, org.jfree.chart.ui.Layer.FOREGROUND);

        SwingUtilities.invokeLater(() -> chartPanel.repaint());
    }


    // ════════════════════════════════════════════════════
    //  가격 포맷 유틸리티
    // ════════════════════════════════════════════════════

    /**
     * 과학적 표기법 없이 소수점 가격을 읽기 쉽게 표시하는 포맷을 반환합니다.
     *
     * 원화(KRW) 기반 코인은 보통 정수지만,
     * SHIB 등 소수점이 많은 코인은 9.15E-3 대신 0.00915 형태로 표시합니다.
     *
     * 전략: 항상 최소 0자리~최대 10자리 소수점 표시, 지수 표기 사용 안 함.
     */
    private DecimalFormat buildPriceFormat() {
        // "#,##0.##########" → 정수부 콤마, 소수점 최대 10자리, 불필요한 0 생략
        DecimalFormat df = new DecimalFormat("#,##0.##########");
        // 지수 표기를 완전히 비활성화하기 위해 multiplier는 기본값(1)으로 유지
        // Java DecimalFormat은 기본적으로 지수를 사용하지 않으므로 이것으로 충분합니다.
        return df;
    }

    /**
     * 가격 값을 화면 표시용 문자열로 변환합니다.
     * 소수점 가격(0.001234 등)도 과학적 표기 없이 그대로 표시합니다.
     */
    private String formatPrice(double price) {
        return buildPriceFormat().format(price);
    }


    // ════════════════════════════════════════════════════
    //  가격 박스 오버레이
    // ════════════════════════════════════════════════════

    private class OverlayChartPanel extends ChartPanel {

        OverlayChartPanel(JFreeChart chart) { super(chart); }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (Double.isNaN(overlayPrice) || plot == null) return;

            Rectangle2D plotArea = getScreenDataArea();
            if (plotArea == null) return;

            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double yPixel = yAxis.valueToJava2D(overlayPrice, plotArea, plot.getRangeAxisEdge());

            int boxW = 110, boxH = 20;
            int boxX = (int) plotArea.getMaxX() + 2;
            int boxY = (int) (yPixel - boxH / 2.0);

            if (boxY < 0 || boxY + boxH > getHeight()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(overlayRising ? Color.RED : Color.BLUE);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 12));
            // ── 소수점 표기 수정: 과학적 표기법 대신 일반 소수점 ──
            String text = formatPrice(overlayPrice);
            FontMetrics fm = g2.getFontMetrics();
            // 텍스트가 박스 너비를 초과하면 boxW를 동적으로 늘림
            int textW = fm.stringWidth(text);
            if (textW + 10 > boxW) {
                g2.setColor(overlayRising ? Color.RED : Color.BLUE);
                g2.fillRoundRect(boxX, boxY, textW + 10, boxH, 4, 4);
                g2.setColor(Color.WHITE);
            }
            g2.drawString(text, boxX + 5, boxY + (boxH + fm.getAscent() - fm.getDescent()) / 2);

            g2.dispose();
        }
    }


    // ════════════════════════════════════════════════════
    //  줌 — 타임프레임 기반 정확한 간격 유지
    // ════════════════════════════════════════════════════

    /**
     * 마우스 휠 줌 처리.
     *
     * ── 기존 문제 ─────────────────────────────────────────
     * 이전 구현은 현재 범위(length)에 비율 factor를 곱했기 때문에
     * 줌 후에도 캔들 간격이 데이터 간격(분봉 ms)과 달라져서
     * CandlestickRenderer의 자동 너비 계산이 어긋났습니다.
     *
     * ── 수정 ──────────────────────────────────────────────
     * 현재 보이는 캔들 수를 계산 → 1개 증감 방식으로 캔들 수를 조절 →
     * 캔들 수 × 캔들 간격(ms)으로 새 범위를 설정합니다.
     * 이렇게 하면 항상 정수 개수의 캔들이 정확한 간격으로 표시됩니다.
     */
    private void handleFixedRightZoom(int wheelRotation) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        OHLCDataset dataset = (OHLCDataset) plot.getDataset();
        if (dataset.getItemCount(0) <= 1) return;

        double candleMs = getCandleIntervalMs(currentTimeframe);
        double upper    = domainAxis.getRange().getUpperBound();
        double length   = domainAxis.getRange().getLength();

        // 현재 표시 중인 캔들 수 추정 (여유 공간 제외)
        int visibleCount = (int) Math.round(length / candleMs);

        // 줌 인: 캔들 수 감소 / 줌 아웃: 캔들 수 증가
        if (wheelRotation > 0) {
            visibleCount = Math.max(5, visibleCount + 5);   // 줌 아웃
        } else {
            visibleCount = Math.max(5, visibleCount - 5);   // 줌 인
        }

        // 새 범위 = 캔들 수 × 캔들 간격 (오른쪽 고정)
        double newLen = visibleCount * candleMs;
        domainAxis.setRange(upper - newLen, upper);

        updateYAxisRange(dataset);
        chartPanel.repaint();
    }


    // ════════════════════════════════════════════════════
    //  라이브 캔들 (실시간)
    // ════════════════════════════════════════════════════

    private void startLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
        liveTimer = new Timer(LIVE_RENDER_MS, e -> onLiveTick());
        liveTimer.start();
    }

    public void stopLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
    }

    /**
     * 500ms마다 호출되는 라이브 틱.
     *
     * ── 로직 ──────────────────────────────────────────────
     *  1) 현재 분봉 시작 시각(currentMinuteStart)을 계산합니다.
     *  2) liveCandleMinuteStart와 다르면 → 분봉이 넘어간 것이므로:
     *     a) 기존 liveCandle을 버림 (DB에 저장하지 않음 — DB가 이미 확정 데이터 가짐)
     *     b) DB에서 직전 분봉 OHLC를 가져와 liveCandle로 대체
     *     c) liveCandleMinuteStart를 현재 분으로 업데이트
     *  3) latestLivePrice로 liveCandle의 종가/고가/저가를 업데이트합니다.
     *  4) 차트를 갱신합니다.
     * ────────────────────────────────────────────────────────
     */
    private void onLiveTick() {
        if (backtestTargetTime != null || latestLivePrice <= 0) return;

        // ★ 수정됨: PC 로컬 시간이 아닌 거래소 서버 타임스탬프 기준 시간 계산
        LocalDateTime serverNow;
        if (latestLiveTimestamp > 0) {
            // 웹소켓에서 받은 서버 타임스탬프(ms)를 LocalDateTime KST로 변환
            serverNow = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(latestLiveTimestamp), java.time.ZoneId.of("Asia/Seoul"));
        } else {
            // 웹소켓 데이터를 아직 못 받았을 때의 임시 방편
            serverNow = LocalDateTime.now();
        }

        // 현재 시간을 분 단위로 절사 (이제 PC 시간이 달라도 거래소 서버 시간에 정확히 맞춰짐)
        LocalDateTime currentMinuteStart = serverNow.truncatedTo(java.time.temporal.ChronoUnit.MINUTES);

        // ── 분봉 경계 감지: 새로운 분으로 넘어갔으면 liveCandle 교체 ──
        if (liveCandleMinuteStart == null || !liveCandleMinuteStart.equals(currentMinuteStart)) {
            liveCandleMinuteStart = currentMinuteStart;
            liveCandle = createNewLiveCandle(latestLivePrice, currentMinuteStart);
        }

        // ── 현재가로 라이브 캔들 업데이트 ──
        double price = latestLivePrice;
        liveCandle.setTradePrice(price);
        liveCandle.setHighPrice(Math.max(liveCandle.getHighPrice(), price));
        liveCandle.setLowPrice(Math.min(liveCandle.getLowPrice(), price));

        // ── 차트 갱신 ──
        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDatasetWithLiveCandle(currentMarket, currentTimeframe);
            plot.setDataset(dataset);
            updateYAxisRange(dataset);
            drawCurrentPriceDashLine(dataset);
        });
    }

    /**
     * 외부(WebSocket 콜백 등)에서 현재가를 주입합니다.
     * 백테스팅 모드에서는 무시됩니다.
     */
    public void setLatestPriceFromWebSocket(double price, long serverTimestamp) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice = price;
        this.latestLiveTimestamp = serverTimestamp; // 서버 시간 갱신
    }

    /**
     * 서버 타임스탬프 없이 가격만 전달받는 경우 (UpbitWebSocket 호환용).
     * trade_price와 함께 ticker의 timestamp 필드를 파싱하도록 UpbitWebSocket을 수정하는 것이 권장되지만,
     * 파싱 실패 시에도 동작하도록 PC 시간 기반 fallback을 유지합니다.
     */
    public void setLatestPriceFromWebSocket(double price) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice = price;
        // serverTimestamp를 -1로 두면 onLiveTick()에서 LocalDateTime.now() fallback 사용
    }

    

    private CandleDTO createNewLiveCandle(double price, LocalDateTime minuteStart) {
        CandleDTO c = new CandleDTO();
        c.setMarket(currentMarket);
        c.setCandleDateTimeKst(minuteStart);
        c.setCandleDateTimeUtc(minuteStart.minusHours(9));
        c.setOpeningPrice(price);
        c.setHighPrice(price);
        c.setLowPrice(price);
        c.setTradePrice(price);
        c.setTimestamp(System.currentTimeMillis());
        c.setUnit(1);
        return c;
    }


    // ════════════════════════════════════════════════════
    //  리샘플링
    // ════════════════════════════════════════════════════

    /**
     * 1시간봉(unit=60) 리스트를 4시간봉으로 리샘플링합니다.
     * KST 자정(00:00) 기준으로 0~3시, 4~7시, 8~11시, 12~15시, 16~19시, 20~23시 블록으로 나눕니다.
     */
    private List<CandleDTO> resample4H(List<CandleDTO> hourList) {
        List<CandleDTO> result = new ArrayList<>();
        List<CandleDTO> group  = new ArrayList<>();
        String groupKey = null;

        for (CandleDTO candle : hourList) {
            String key = calcBlockKey(candle.getCandleDateTimeKst(), TF_4H);
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(merge4HGroup(group));
                group.clear();
                groupKey = key;
            }
            group.add(candle);
        }
        if (!group.isEmpty()) result.add(merge4HGroup(group));
        return result;
    }

    private CandleDTO merge4HGroup(List<CandleDTO> group) {
        CandleDTO first = group.get(0);
        CandleDTO last  = group.get(group.size() - 1);

        // 블록 시작 시각: KST 기준 4시간 경계 (0, 4, 8, 12, 16, 20시)
        LocalDateTime kst = first.getCandleDateTimeKst();
        LocalDateTime KST_EPOCH = LocalDateTime.of(1970, 1, 1, 9, 0);
        long epochMinutes = ChronoUnit.MINUTES.between(KST_EPOCH, kst);
        long blockStart   = (epochMinutes / TF_4H) * TF_4H;
        LocalDateTime blockStartTime = KST_EPOCH.plusMinutes(blockStart);

        CandleDTO m = new CandleDTO();
        m.setMarket(first.getMarket());
        m.setCandleDateTimeKst(blockStartTime);
        m.setCandleDateTimeUtc(blockStartTime.minusHours(9));
        m.setOpeningPrice(first.getOpeningPrice());
        m.setTradePrice(last.getTradePrice());
        m.setHighPrice(group.stream().mapToDouble(CandleDTO::getHighPrice).max().orElse(0));
        m.setLowPrice(group.stream().mapToDouble(CandleDTO::getLowPrice).min().orElse(0));
        m.setCandleAccTradeVolume(group.stream().mapToDouble(CandleDTO::getCandleAccTradeVolume).sum());
        m.setUnit(TF_4H);
        return m;
    }
    
    
    private OHLCDataset buildDataset(String market, List<CandleDTO> list) {
        int count = list.size();
        Date[]   date   = new Date[count];
        double[] high   = new double[count], low   = new double[count];
        double[] open   = new double[count], close = new double[count];
        double[] volume = new double[count];

        for (int i = 0; i < count; i++) {
            CandleDTO dto = list.get(i);
            date[i]   = java.sql.Timestamp.valueOf(dto.getCandleDateTimeKst());
            open[i]   = dto.getOpeningPrice();
            high[i]   = dto.getHighPrice();
            low[i]    = dto.getLowPrice();
            close[i]  = dto.getTradePrice();
            volume[i] = dto.getCandleAccTradeVolume();
        }
        return new DefaultHighLowDataset(market, date, high, low, open, close, volume);
    }


    // ════════════════════════════════════════════════════
    //  웹소켓
    // ════════════════════════════════════════════════════

    private void connectWebSocket() {
        disconnectWebSocket();
        webSocketClient = new UpbitWebSocket(currentMarket, this);
        webSocketClient.connect();
    }

    private void disconnectWebSocket() {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            webSocketClient = null;
        }
    }


    /** 코인 종목 변경 */
    public void changeMarket(String coinSymbol) {
        this.currentMarket = "KRW-" + coinSymbol;
        liveCandle = null;
        liveCandleMinuteStart = null;
        refreshChart();
        if (backtestTargetTime == null) {
            connectWebSocket();
        }
    }

    /** 백테스팅 모드로 전환 */
    public void loadHistoricalData(LocalDateTime targetTime) {
        stopLiveTimer();
        disconnectWebSocket();
        liveCandle = null;
        liveCandleMinuteStart = null;
        this.backtestTargetTime = targetTime;

        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, targetTime);
            plot.setDataset(dataset);
            updateXAxisRange(dataset);
            updateYAxisRange(dataset);
            drawCurrentPriceDashLine(dataset);
            applyDateAxisFormat();
            chart.setTitle(getCurrentMarketSymbol() + " (Backtesting)");
        });
    }

    /** 실시간 모드로 복귀 */
    public void resetToRealtimeMode() {
        this.backtestTargetTime = null;
        liveCandle = null;
        liveCandleMinuteStart = null;
        startLiveTimer();
        connectWebSocket();
        refreshChart();
    }

    private String getCurrentMarketSymbol() {
        return currentMarket.replace("KRW-", "");
    }
}
