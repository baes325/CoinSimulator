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
 *  4. 백테스팅 모드: targetTime 기준 과거 데이터만 표시
 *     ★ 타임프레임 변경 시에도 백테스팅 시점 유지
 *  5. DB에서 1분봉 기준으로 조회 후 타임프레임에 맞게 리샘플링
 *     - 지원 타임프레임: 1분 / 30분 / 1시간 / 4시간 / 1일 / 1달
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
    private static final int DISPLAY_1M   = 60;
    private static final int DISPLAY_30M  = 60;
    private static final int DISPLAY_1H   = 55;
    private static final int DISPLAY_4H   = 55;
    private static final int DISPLAY_1D   = 55;
    private static final int DISPLAY_1MON = 24;

    /**
     * DB에서 가져올 1분봉 최대 개수.
     * 1달봉 24개를 만들려면 최소 24*30*24*60 = 1,036,800건이 필요하지만
     * 현실적으로 5000~50000 범위에서 조절하세요.
     */
    private static final int DB_FETCH_LIMIT = 5000;

    // ── 차트 컴포넌트 ────────────────────────────────────
    private JFreeChart chart;
    private XYPlot plot;
    private OverlayChartPanel chartPanel;
    private CandleDAO candleDAO = new CandleDAO();
    private JButton selectedButton;

    // ── 현재 상태 ────────────────────────────────────────
    private String currentMarket = "KRW-BTC";
    private int currentTimeframe = TF_4H; // 기본 4시간봉
    private Point lastMousePoint;

    // ── 백테스팅 상태 ─────────────────────────────────────
    /**
     * 백테스팅 모드일 때 설정되는 기준 시각.
     * null = 실시간 모드.
     *
     * ★ 핵심: refreshChart()는 항상 이 필드를 참조합니다.
     *   → 타임프레임 버튼을 눌러도 백테스팅 시점이 유지됩니다.
     *   → loadHistoricalData()로 설정, resetToRealtimeMode()로 초기화.
     */
    private LocalDateTime backtestTargetTime = null;

    // ── 실시간 라이브 캔들 ────────────────────────────────
    private CandleDTO liveCandle = null;
    private LocalDateTime liveCandleEndTime = null;

    // ── 실시간 타이머 ────────────────────────────────────
    private Timer liveTimer;
    private static final int LIVE_TICK_MS = 3000;

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
    }


    // ════════════════════════════════════════════════════
    //  차트 UI 설정
    // ════════════════════════════════════════════════════

    private void configureChartUI() {
        plot.setRangeAxisLocation(AxisLocation.TOP_OR_RIGHT);
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setAutoRangeIncludesZero(false);

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM/dd HH:mm"));
        domainAxis.setTickLabelFont(new Font("Nanum Gothic", Font.PLAIN, 11));
        domainAxis.setAutoTickUnitSelection(true);

        CandlestickRenderer renderer = new CandlestickRenderer();
        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST);
        renderer.setAutoWidthGap(-0.1);
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
     * 실시간/백테스팅 모드에 따라 1분봉을 조회하고 리샘플링합니다.
     *
     * @param market     종목 코드
     * @param timeframe  타임프레임 (분, TF_* 상수)
     * @param targetTime 백테스팅 기준 시각 (null = 실시간)
     */
    private OHLCDataset createDataset(String market, int timeframe, LocalDateTime targetTime) {
        List<CandleDTO> rawList;

        if (targetTime != null) {
            // 백테스팅: targetTime 이전의 1분봉만 조회
            rawList = candleDAO.getHistoricalCandles(market, 1, targetTime, DB_FETCH_LIMIT);
        } else {
            // 실시간: 최신 1분봉
            rawList = candleDAO.getCandles(market, 1, DB_FETCH_LIMIT);
        }

        if (rawList.isEmpty()) return emptyDataset(market);

        // DB 조회는 DESC → ASC로 뒤집기
        Collections.reverse(rawList);

        List<CandleDTO> resampled = resampleCandles(rawList, timeframe);
        return buildDataset(market, resampled);
    }

    /** 실시간 모드 전용: liveCandle을 포함한 데이터셋 생성 */
    private OHLCDataset createDatasetWithLiveCandle(String market, int timeframe) {
        List<CandleDTO> rawList = candleDAO.getCandles(market, 1, DB_FETCH_LIMIT);
        if (rawList.isEmpty() && liveCandle == null) return emptyDataset(market);

        Collections.reverse(rawList);
        List<CandleDTO> resampled = resampleCandles(rawList, timeframe);
        if (liveCandle != null) resampled.add(liveCandle);
        return buildDataset(market, resampled);
    }

    private OHLCDataset emptyDataset(String market) {
        return new DefaultHighLowDataset(market,
                new Date[0], new double[0], new double[0],
                new double[0], new double[0], new double[0]);
    }


    // ════════════════════════════════════════════════════
    //  차트 갱신
    // ════════════════════════════════════════════════════

    /**
     * 현재 모드(실시간/백테스팅)와 타임프레임으로 차트를 갱신합니다.
     *
     * ★ backtestTargetTime이 null이 아니면 백테스팅 데이터를 사용합니다.
     *   타임프레임 버튼 → refreshChart() 호출 시에도 동일하게 동작합니다.
     */
    private void refreshChart() {
        // backtestTargetTime을 그대로 전달 → null이면 실시간, 값이 있으면 백테스팅
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

    private void updateXAxisRange(OHLCDataset dataset) {
        int itemCount = dataset.getItemCount(0);
        if (itemCount <= 0) return;

        int displayCount = Math.min(itemCount, getDisplayCount(currentTimeframe));

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double lastTick  = dataset.getXValue(0, itemCount - 1);
        double firstTick = dataset.getXValue(0, itemCount - displayCount);
        double oneBarInterval = (displayCount > 1)
                ? (lastTick - firstTick) / (displayCount - 1) : 1;

        domainAxis.setRange(firstTick - oneBarInterval * 0.5, lastTick + oneBarInterval * 4.5);
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
    //  가격 박스 오버레이
    // ════════════════════════════════════════════════════

    private class OverlayChartPanel extends ChartPanel {

        private final DecimalFormat fmt = new DecimalFormat("#,###.#####");

        OverlayChartPanel(JFreeChart chart) { super(chart); }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);

            if (Double.isNaN(overlayPrice) || plot == null) return;

            Rectangle2D plotArea = getScreenDataArea();
            if (plotArea == null) return;

            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double yPixel = yAxis.valueToJava2D(overlayPrice, plotArea, plot.getRangeAxisEdge());

            int boxW = 95, boxH = 20;
            int boxX = (int) plotArea.getMaxX() + 2;
            int boxY = (int) (yPixel - boxH / 2.0);

            if (boxY < 0 || boxY + boxH > getHeight()) return;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            g2.setColor(overlayRising ? Color.RED : Color.BLUE);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 13));
            String text = fmt.format(overlayPrice);
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(text, boxX + 5, boxY + (boxH + fm.getAscent() - fm.getDescent()) / 2);

            g2.dispose();
        }
    }


    // ════════════════════════════════════════════════════
    //  줌
    // ════════════════════════════════════════════════════

    private void handleFixedRightZoom(int wheelRotation) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        OHLCDataset dataset = (OHLCDataset) plot.getDataset();
        if (dataset.getItemCount(0) <= 1) return;

        double upper    = domainAxis.getRange().getUpperBound();
        double length   = domainAxis.getRange().getLength();
        double factor   = (wheelRotation > 0) ? 1.1 : 0.9;
        double newLen   = length * factor;

        domainAxis.setRange(upper - newLen, upper);
        updateYAxisRange(dataset);
        chartPanel.repaint();
    }


    // ════════════════════════════════════════════════════
    //  라이브 캔들 (실시간)
    // ════════════════════════════════════════════════════

    private void startLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
        liveTimer = new Timer(LIVE_TICK_MS, e -> onLiveTick());
        liveTimer.start();
    }

    public void stopLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
    }

    private void onLiveTick() {
        // TODO: updateLivePrice(UpbitAPI.getTicker(currentMarket));
    }

    /**
     * 외부(WebSocket 콜백 등)에서 현재가를 주입합니다.
     * 백테스팅 모드에서는 무시됩니다.
     */
    public void updateLivePrice(double price) {
        if (backtestTargetTime != null) return; // 백테스팅 중 무시

        LocalDateTime now = LocalDateTime.now();

        if (liveCandle == null) {
            liveCandle = createNewLiveCandle(price, now);
            liveCandleEndTime = calculateCandleEndTime(now);
        }

        if (now.isAfter(liveCandleEndTime)) {
            finalizeLiveCandle();
            liveCandle = createNewLiveCandle(price, now);
            liveCandleEndTime = calculateCandleEndTime(now);
        }

        liveCandle.setTradePrice(price);
        liveCandle.setHighPrice(Math.max(liveCandle.getHighPrice(), price));
        liveCandle.setLowPrice(Math.min(liveCandle.getLowPrice(), price));

        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDatasetWithLiveCandle(currentMarket, currentTimeframe);
            plot.setDataset(dataset);
            drawCurrentPriceDashLine(dataset);
        });
    }

    private void finalizeLiveCandle() {
        if (liveCandle == null) return;
        liveCandle.setUnit(1); // 1분봉으로 저장
        candleDAO.insertCandles(Collections.singletonList(liveCandle));
        liveCandle = null;
    }

    private CandleDTO createNewLiveCandle(double price, LocalDateTime time) {
        CandleDTO c = new CandleDTO();
        c.setMarket(currentMarket);
        c.setCandleDateTimeKst(time);
        c.setCandleDateTimeUtc(time.minusHours(9));
        c.setOpeningPrice(price);
        c.setHighPrice(price);
        c.setLowPrice(price);
        c.setTradePrice(price);
        c.setTimestamp(System.currentTimeMillis());
        c.setUnit(1);
        return c;
    }

    private LocalDateTime calculateCandleEndTime(LocalDateTime now) {
        int totalMinutes = (currentTimeframe == TF_1MON) ? 60 * 24 * 30 : currentTimeframe;
        long minutesSinceEpoch = ChronoUnit.MINUTES.between(LocalDateTime.of(1970, 1, 1, 0, 0), now);
        long blockStart = (minutesSinceEpoch / totalMinutes) * totalMinutes;
        return LocalDateTime.of(1970, 1, 1, 0, 0).plusMinutes(blockStart + totalMinutes);
    }


    // ════════════════════════════════════════════════════
    //  리샘플링
    // ════════════════════════════════════════════════════

    /**
     * 1분봉 리스트를 지정된 타임프레임(분)으로 리샘플링합니다.
     *
     * - 1분봉: 그대로 반환
     * - 1달봉: "yyyy-MM" 기준으로 월별 그룹핑
     * - 그 외:  epoch 분 / timeframe 으로 블록 키를 산출해 그룹핑
     *
     * @param rawList         1분봉 리스트 (시간 오름차순)
     * @param timeframeMinutes 목표 타임프레임 (분)
     */
    private List<CandleDTO> resampleCandles(List<CandleDTO> rawList, int timeframeMinutes) {
        if (timeframeMinutes <= 1) return new ArrayList<>(rawList);

        boolean isMonthly = (timeframeMinutes == TF_1MON);
        List<CandleDTO> result = new ArrayList<>();
        List<CandleDTO> group  = new ArrayList<>();
        String groupKey = null;

        for (CandleDTO candle : rawList) {
            LocalDateTime kst = candle.getCandleDateTimeKst();
            String key;

            if (isMonthly) {
                key = kst.getYear() + "-" + String.format("%02d", kst.getMonthValue());
            } else {
                long epochMinutes = ChronoUnit.MINUTES.between(
                        LocalDateTime.of(1970, 1, 1, 0, 0), kst);
                key = Long.toString(epochMinutes / timeframeMinutes);
            }

            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) {
                    result.add(mergeGroup(group));
                    group.clear();
                }
                groupKey = key;
            }
            group.add(candle);
        }
        if (!group.isEmpty()) result.add(mergeGroup(group));

        return result;
    }

    private CandleDTO mergeGroup(List<CandleDTO> group) {
        CandleDTO first = group.get(0);
        CandleDTO last  = group.get(group.size() - 1);
        CandleDTO m = new CandleDTO();
        m.setMarket(first.getMarket());
        m.setCandleDateTimeKst(first.getCandleDateTimeKst());
        m.setCandleDateTimeUtc(first.getCandleDateTimeUtc());
        m.setOpeningPrice(first.getOpeningPrice());
        m.setTradePrice(last.getTradePrice());
        m.setHighPrice(group.stream().mapToDouble(CandleDTO::getHighPrice).max().orElse(0));
        m.setLowPrice(group.stream().mapToDouble(CandleDTO::getLowPrice).min().orElse(0));
        m.setCandleAccTradeVolume(group.stream().mapToDouble(CandleDTO::getCandleAccTradeVolume).sum());
        m.setUnit(currentTimeframe);
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
    //  공개 API
    // ════════════════════════════════════════════════════

    /**
     * 코인 종목 변경 (현재 실시간/백테스팅 모드 유지)
     */
    public void changeMarket(String coinSymbol) {
        this.currentMarket = "KRW-" + coinSymbol;
        liveCandle = null;
        refreshChart();
    }

    /**
     * 백테스팅 모드로 전환 — 지정 시점 이전 데이터만 표시합니다.
     * MainFrame.onTimeChanged(isRealtime=false) 에서 호출됩니다.
     *
     * ★ backtestTargetTime을 저장하므로, 이후 타임프레임 버튼을
     *   눌러도 refreshChart()가 동일 시점을 유지합니다.
     */
    public void loadHistoricalData(LocalDateTime targetTime) {
        stopLiveTimer();
        liveCandle = null;

        // ★ 핵심: 저장
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

    /**
     * 실시간 모드로 복귀합니다.
     * MainFrame.onTimeChanged(isRealtime=true) 에서 호출됩니다.
     */
    public void resetToRealtimeMode() {
        // ★ 핵심: 초기화
        this.backtestTargetTime = null;
        liveCandle = null;
        startLiveTimer();
        refreshChart();
    }

    private String getCurrentMarketSymbol() {
        return currentMarket.replace("KRW-", "");
    }
}