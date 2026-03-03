package com.team.coin_simulator.chart;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 *  5. ★ 모든 타임프레임 뷰포트 페이징
 *  6. ★ 보조지표: MA5/MA20/MA60, 볼린저 밴드, 거래량 오버레이 (드롭다운 ON/OFF)
 * ────────────────────────────────────────────────────────
 */
public class CandleChartPanel extends JPanel {

    // ── 타임프레임 정의 (분 단위) ────────────────────────
    private static final int TF_1M   = 1;
    private static final int TF_30M  = 30;
    private static final int TF_1H   = 60;
    private static final int TF_4H   = 240;
    private static final int TF_1D   = 1440;
    private static final int TF_1MON = 43200;

    private static final int DISPLAY_1M   = 55;
    private static final int DISPLAY_30M  = 55;
    private static final int DISPLAY_1H   = 55;
    private static final int DISPLAY_4H   = 55;
    private static final int DISPLAY_1D   = 55;
    private static final int DISPLAY_1MON = 55;

    private static final int    PAGE_BUFFER        = 300;
    private static final double PREFETCH_THRESHOLD = 0.25;
    private static final int    MIN_VISIBLE_CANDLES = 5;
    private static final int    MAX_VISIBLE_CANDLES = 200;

    // ── 보조지표 ON/OFF 상태 ─────────────────────────────
    private boolean showMA5       = false;
    private boolean showMA20      = false;
    private boolean showMA60      = false;
    private boolean showBollinger = false;
    private boolean showVolume    = false;

    /** 볼린저 밴드 파라미터 */
    private static final int    BB_PERIOD     = 20;
    private static final double BB_MULTIPLIER = 2.0;

    /** 거래량 패널 높이 비율 (차트 전체 대비) */
    private static final double VOLUME_PANEL_RATIO = 0.18;

    // ── 관심 코인 관련 필드 ──────────────────────────────
    private String currentUserId;
    private Runnable watchlistListener;
    private JButton btnWatchlist;
    private DAO.WatchListDAO watchListDAO = new DAO.WatchListDAO();

    // ── 타임프레임별 페이지 캐시 ─────────────────────────
    private static class PageCache {
        List<CandleDTO> candles = new ArrayList<>();
        long fromMs = -1;
        long toMs   = -1;
        volatile boolean prefetchInProgress = false;

        void reset() {
            candles = new ArrayList<>();
            fromMs = -1;
            toMs   = -1;
            prefetchInProgress = false;
        }

        boolean isInitialized() { return fromMs >= 0 && toMs >= 0; }
    }

    private final Map<Integer, PageCache> cacheMap = new HashMap<>();

    // ── 차트 컴포넌트 ────────────────────────────────────
    private JFreeChart chart;
    private XYPlot plot;
    private OverlayChartPanel chartPanel;
    private CandleDAO candleDAO = new CandleDAO();
    private JButton selectedButton;
    private JLabel lblChartTitle;

    // ── 초기 상태 ────────────────────────────────────────
    private String currentMarket   = "KRW-BTC";
    private int    currentTimeframe = TF_1M;
    private Point  lastMousePoint;

    // ── 백테스팅 상태 ─────────────────────────────────────
    private LocalDateTime backtestTargetTime = null;

    // ── 실시간 라이브 캔들 ────────────────────────────────
    private CandleDTO     liveCandle            = null;
    private LocalDateTime liveCandleMinuteStart = null;

    // ── Ghost 캔들 ───────────────────────────────────────
    private final List<CandleDTO> ghostCandles  = new ArrayList<>();
    private static final int MAX_GHOST_CANDLES  = 10;

    // ── 실시간 타이머 ────────────────────────────────────
    private Timer liveTimer;
    private static final int LIVE_RENDER_MS = 500;
    private volatile double latestLivePrice     = -1;
    private volatile long   latestLiveTimestamp = -1;

    // ── 웹소켓 ───────────────────────────────────────────
    private UpbitWebSocket webSocketClient;

    // ── 현재가 박스 표시 ──────────────────────────────────
    private double  overlayPrice  = Double.NaN;
    private boolean overlayRising = true;


    // ════════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════════
    public CandleChartPanel(String title) {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        initCacheMap();

        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, null);
        chart = ChartFactory.createCandlestickChart(null, "", "", dataset, false);
        chart.setTitle("");
        plot = (XYPlot) chart.getPlot();

        configureChartUI();
        setupChartPanel();
        createTopArea();

        add(chartPanel, BorderLayout.CENTER);

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        updateCandleWidth();
        drawCurrentPriceDashLine(dataset);

        startLiveTimer();
        connectWebSocket();
    }

    private void initCacheMap() {
        for (int tf : new int[]{TF_1M, TF_30M, TF_1H, TF_1D, TF_1MON}) {
            cacheMap.put(tf, new PageCache());
        }
    }

    public void setUserIdAndListener(String userId, Runnable listener) {
        this.currentUserId    = userId;
        this.watchlistListener = listener;
        updateWatchlistButtonState();
    }


    // ════════════════════════════════════════════════════
    //  차트 UI 설정
    // ════════════════════════════════════════════════════

    private void configureChartUI() {
        plot.setRangeAxisLocation(AxisLocation.TOP_OR_RIGHT);
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
        yAxis.setAutoRangeIncludesZero(false);
        yAxis.setNumberFormatOverride(buildPriceFormat());

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        domainAxis.setDateFormatOverride(new SimpleDateFormat("MM/dd HH:mm"));
        domainAxis.setTickLabelFont(new Font("Nanum Gothic", Font.PLAIN, 11));
        domainAxis.setAutoTickUnitSelection(true);

        CandlestickRenderer renderer = new CandlestickRenderer();
        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_AVERAGE);
        renderer.setAutoWidthFactor(0.7);
        renderer.setAutoWidthGap(0.0);
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
                checkAndPrefetchIfNeeded(currentTimeframe);
                chartPanel.repaint();
            }
        });
    }

    // ════════════════════════════════════════════════════
    //  상단 영역 (타임프레임 버튼 + 보조지표 드롭다운)
    // ════════════════════════════════════════════════════

    private void createTopArea() {
        JPanel topContainer = new JPanel();
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        topContainer.setBackground(Color.WHITE);

        // ── 1행: 타임프레임 버튼(왼쪽) + 보조지표 드롭다운(오른쪽) ──
        JPanel buttonRow = new JPanel(new BorderLayout());
        buttonRow.setBackground(Color.WHITE);

        // 타임프레임 버튼 패널
        JPanel tfPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        tfPanel.setBackground(Color.WHITE);

        String[] labels     = {"1분",  "30분",  "1시간", "4시간", "1일",   "1달"};
        int[]    timeframes = {TF_1M, TF_30M, TF_1H,  TF_4H,  TF_1D, TF_1MON};

        for (int i = 0; i < labels.length; i++) {
            final int timeframe = timeframes[i];
            JButton button = new JButton(labels[i]);
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
                resetCache(dbUnitOf(timeframe));
                refreshChart();
            });
            tfPanel.add(button);
        }

        // 보조지표 드롭다운 패널
        JPanel indicatorPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 4));
        indicatorPanel.setBackground(Color.WHITE);
        indicatorPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 90));
        indicatorPanel.add(createIndicatorDropdown());

        buttonRow.add(tfPanel,        BorderLayout.WEST);
        buttonRow.add(indicatorPanel, BorderLayout.EAST);

        // ── 2행: 코인 이름 + 관심코인 ──
        JPanel titlePanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        titlePanel.setBackground(Color.WHITE);

        lblChartTitle = new JLabel(getCurrentMarketSymbol());
        lblChartTitle.setFont(new Font("맑은 고딕", Font.BOLD, 20));

        btnWatchlist = new JButton("☆");
        btnWatchlist.setFont(new Font("맑은 고딕", Font.BOLD, 22));
        btnWatchlist.setForeground(new Color(241, 196, 15));
        btnWatchlist.setBackground(Color.WHITE);
        btnWatchlist.setFocusPainted(false);
        btnWatchlist.setBorderPainted(false);
        btnWatchlist.setContentAreaFilled(false);
        btnWatchlist.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btnWatchlist.setMargin(new Insets(0, 0, 0, 0));
        btnWatchlist.addActionListener(e -> toggleWatchlist());

        titlePanel.add(lblChartTitle);
        titlePanel.add(btnWatchlist);

        topContainer.add(buttonRow);
        topContainer.add(titlePanel);
        add(topContainer, BorderLayout.NORTH);
    }

    /**
     * 보조지표 드롭다운 버튼을 생성합니다.
     * 버튼 클릭 시 팝업 메뉴가 나타나며 각 항목을 체크박스로 ON/OFF합니다.
     */
    private JButton createIndicatorDropdown() {
        JButton btn = new JButton("보조지표");
        btn.setFocusPainted(false);
        btn.setBackground(Color.LIGHT_GRAY);
        btn.setForeground(Color.BLACK);
        btn.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        btn.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        //btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        JPopupMenu popup = buildIndicatorPopup(btn);

        btn.addActionListener(e -> {
            popup.show(btn, 0, btn.getHeight());
        });

        return btn;
    }

    /**
     * 보조지표 팝업 메뉴 구성.
     * ─ MA5 / MA20 / MA60 / 볼린저 밴드 / 거래량
     */
    private JPopupMenu buildIndicatorPopup(JButton parentBtn) {
        JPopupMenu popup = new JPopupMenu();
        popup.setBackground(Color.WHITE);
        popup.setBorder(BorderFactory.createLineBorder(new Color(200, 200, 200)));

        // ── 섹션 레이블 ──
        JLabel lblMA = new JLabel("  이동평균선");
        lblMA.setFont(new Font("맑은 고딕", Font.BOLD, 11));
        lblMA.setForeground(new Color(100, 100, 100));
        lblMA.setBorder(BorderFactory.createEmptyBorder(6, 4, 2, 4));
        popup.add(lblMA);

        // MA5
        JCheckBoxMenuItem itemMA5 = new JCheckBoxMenuItem("MA 5", showMA5);
        styleMenuItem(itemMA5, new Color(255, 165, 0)); // 주황
        itemMA5.addActionListener(e -> {
            showMA5 = itemMA5.isSelected();
            chartPanel.repaint();
        });
        popup.add(itemMA5);

        // MA20
        JCheckBoxMenuItem itemMA20 = new JCheckBoxMenuItem("MA 20", showMA20);
        styleMenuItem(itemMA20, new Color(30, 144, 255)); // 파랑
        itemMA20.addActionListener(e -> {
            showMA20 = itemMA20.isSelected();
            chartPanel.repaint();
        });
        popup.add(itemMA20);

        // MA60
        JCheckBoxMenuItem itemMA60 = new JCheckBoxMenuItem("MA 60", showMA60);
        styleMenuItem(itemMA60, new Color(148, 0, 211)); // 보라
        itemMA60.addActionListener(e -> {
            showMA60 = itemMA60.isSelected();
            chartPanel.repaint();
        });
        popup.add(itemMA60);

        popup.addSeparator();

        // ── 볼린저 밴드 ──
        JLabel lblBB = new JLabel("  볼린저 밴드");
        lblBB.setFont(new Font("맑은 고딕", Font.BOLD, 11));
        lblBB.setForeground(new Color(100, 100, 100));
        lblBB.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        popup.add(lblBB);

        JCheckBoxMenuItem itemBB = new JCheckBoxMenuItem("Bollinger Bands (20,2)", showBollinger);
        styleMenuItem(itemBB, new Color(34, 139, 34)); // 초록
        itemBB.addActionListener(e -> {
            showBollinger = itemBB.isSelected();
            chartPanel.repaint();
        });
        popup.add(itemBB);

        popup.addSeparator();

        // ── 거래량 ──
        JLabel lblVol = new JLabel("  거래량");
        lblVol.setFont(new Font("맑은 고딕", Font.BOLD, 11));
        lblVol.setForeground(new Color(100, 100, 100));
        lblVol.setBorder(BorderFactory.createEmptyBorder(4, 4, 2, 4));
        popup.add(lblVol);

        JCheckBoxMenuItem itemVol = new JCheckBoxMenuItem("Volume", showVolume);
        styleMenuItem(itemVol, new Color(128, 128, 128)); // 회색
        itemVol.addActionListener(e -> {
            showVolume = itemVol.isSelected();
            chartPanel.repaint();
        });
        popup.add(itemVol);

        return popup;
    }

    /** 메뉴 아이템 공통 스타일 */
    private void styleMenuItem(JCheckBoxMenuItem item, Color accentColor) {
        item.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        item.setBackground(Color.WHITE);
        item.setForeground(Color.DARK_GRAY);
        item.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
        // 체크 시 글씨색을 강조색으로
        item.addChangeListener(e -> {
            item.setForeground(item.isSelected() ? accentColor : Color.DARK_GRAY);
        });
    }


    // ════════════════════════════════════════════════════
    //  ★ 보조지표 계산
    // ════════════════════════════════════════════════════

    /**
     * 단순 이동평균 (SMA).
     * 데이터가 period보다 적은 앞부분은 Double.NaN으로 채웁니다.
     *
     * @param closes 종가 배열 (시간 오름차순)
     * @param period 기간
     * @return 동일 길이의 MA 배열
     */
    private double[] calcMA(double[] closes, int period) {
        double[] ma = new double[closes.length];
        for (int i = 0; i < closes.length; i++) {
            if (i < period - 1) {
                ma[i] = Double.NaN;
            } else {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) sum += closes[j];
                ma[i] = sum / period;
            }
        }
        return ma;
    }

    /**
     * 볼린저 밴드 계산.
     *
     * @param closes 종가 배열
     * @param period 이동평균 기간 (기본 20)
     * @param mult   표준편차 배수 (기본 2.0)
     * @return [0]=상단밴드, [1]=중간(MA), [2]=하단밴드
     */
    private double[][] calcBollingerBands(double[] closes, int period, double mult) {
        int n = closes.length;
        double[] upper  = new double[n];
        double[] middle = new double[n];
        double[] lower  = new double[n];

        for (int i = 0; i < n; i++) {
            if (i < period - 1) {
                upper[i] = middle[i] = lower[i] = Double.NaN;
            } else {
                double sum = 0;
                for (int j = i - period + 1; j <= i; j++) sum += closes[j];
                double avg = sum / period;
                double variance = 0;
                for (int j = i - period + 1; j <= i; j++) {
                    double diff = closes[j] - avg;
                    variance += diff * diff;
                }
                double stdDev = Math.sqrt(variance / period);
                middle[i] = avg;
                upper[i]  = avg + mult * stdDev;
                lower[i]  = avg - mult * stdDev;
            }
        }
        return new double[][]{upper, middle, lower};
    }


    // ════════════════════════════════════════════════════
    //  ★ 보조지표 렌더링 (OverlayChartPanel 내부에서 호출)
    // ════════════════════════════════════════════════════

    /**
     * 현재 dataset의 종가/거래량 배열을 추출하고
     * 활성화된 보조지표를 Graphics2D 위에 그립니다.
     */
    private void drawIndicators(Graphics2D g2, Rectangle2D plotArea) {
        OHLCDataset dataset = (OHLCDataset) plot.getDataset();
        if (dataset == null || dataset.getItemCount(0) <= 0) return;

        int n = dataset.getItemCount(0);

        // ── X/Y 매핑용 축 ──
        DateAxis   xAxis = (DateAxis)   plot.getDomainAxis();
        NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();

        // ── 종가 배열 추출 ──
        double[] closes  = new double[n];
        double[] volumes = new double[n];
        double[] xMs     = new double[n];
        for (int i = 0; i < n; i++) {
            closes[i]  = dataset.getCloseValue(0, i);
            volumes[i] = dataset.getVolumeValue(0, i);
            xMs[i]     = dataset.getXValue(0, i);
        }

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // ── 볼린저 밴드 (MA선 아래에 먼저 그려 MA가 위에 올라오게) ──
        if (showBollinger) {
            drawBollingerBands(g2, plotArea, xAxis, yAxis, xMs, closes);
        }

        // ── 이동평균선 ──
        if (showMA5)  drawMALine(g2, plotArea, xAxis, yAxis, xMs, calcMA(closes, 5),
                                  new Color(255, 165, 0), "MA5");
        if (showMA20) drawMALine(g2, plotArea, xAxis, yAxis, xMs, calcMA(closes, 20),
                                  new Color(30, 144, 255), "MA20");
        if (showMA60) drawMALine(g2, plotArea, xAxis, yAxis, xMs, calcMA(closes, 60),
                                  new Color(148, 0, 211), "MA60");

        // ── 거래량 (캔들 차트 하단 18% 영역에 오버레이) ──
        if (showVolume) {
            drawVolumeOverlay(g2, plotArea, xAxis, dataset, xMs, volumes);
        }
    }

    /**
     * 단일 MA 선 + 범례 레이블 렌더링.
     */
    private void drawMALine(Graphics2D g2, Rectangle2D plotArea,
                             DateAxis xAxis, NumberAxis yAxis,
                             double[] xMs, double[] ma,
                             Color color, String label) {
        g2.setColor(color);
        g2.setStroke(new BasicStroke(1.5f));

        int prevX = -1, prevY = -1;
        for (int i = 0; i < ma.length; i++) {
            if (Double.isNaN(ma[i])) { prevX = prevY = -1; continue; }

            int px = (int) xAxis.valueToJava2D(xMs[i], plotArea, plot.getDomainAxisEdge());
            int py = (int) yAxis.valueToJava2D(ma[i],  plotArea, plot.getRangeAxisEdge());

            if (prevX >= 0) {
                g2.drawLine(prevX, prevY, px, py);
            }
            prevX = px;
            prevY = py;
        }

        // 마지막 점 옆에 레이블
        if (prevX >= 0) {
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 10));
            g2.drawString(label, prevX + 3, prevY - 3);
        }
    }

    /**
     * 볼린저 밴드 (상단/중간/하단 + 반투명 채움) 렌더링.
     */
    private void drawBollingerBands(Graphics2D g2, Rectangle2D plotArea,
                                     DateAxis xAxis, NumberAxis yAxis,
                                     double[] xMs, double[] closes) {
        double[][] bb = calcBollingerBands(closes, BB_PERIOD, BB_MULTIPLIER);
        double[] upper  = bb[0];
        double[] middle = bb[1];
        double[] lower  = bb[2];

        Color bandColor = new Color(34, 139, 34);

        // ── 밴드 내부 채움 (반투명) ──
        java.awt.geom.GeneralPath fillPath = new java.awt.geom.GeneralPath();
        boolean started = false;
        for (int i = 0; i < upper.length; i++) {
            if (Double.isNaN(upper[i])) continue;
            int px = (int) xAxis.valueToJava2D(xMs[i],    plotArea, plot.getDomainAxisEdge());
            int py = (int) yAxis.valueToJava2D(upper[i],  plotArea, plot.getRangeAxisEdge());
            if (!started) { fillPath.moveTo(px, py); started = true; }
            else           fillPath.lineTo(px, py);
        }
        for (int i = lower.length - 1; i >= 0; i--) {
            if (Double.isNaN(lower[i])) continue;
            int px = (int) xAxis.valueToJava2D(xMs[i],   plotArea, plot.getDomainAxisEdge());
            int py = (int) yAxis.valueToJava2D(lower[i], plotArea, plot.getRangeAxisEdge());
            fillPath.lineTo(px, py);
        }
        fillPath.closePath();
        g2.setColor(new Color(34, 139, 34, 25)); // 매우 연한 초록
        g2.fill(fillPath);

        // ── 상단/하단 밴드 선 ──
        g2.setColor(new Color(bandColor.getRed(), bandColor.getGreen(), bandColor.getBlue(), 180));
        g2.setStroke(new BasicStroke(1.0f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND,
                1.0f, new float[]{4f, 2f}, 0f));
        drawSimpleLine(g2, plotArea, xAxis, yAxis, xMs, upper);
        drawSimpleLine(g2, plotArea, xAxis, yAxis, xMs, lower);

        // ── 중간선 (실선) ──
        g2.setColor(new Color(bandColor.getRed(), bandColor.getGreen(), bandColor.getBlue(), 210));
        g2.setStroke(new BasicStroke(1.2f));
        drawSimpleLine(g2, plotArea, xAxis, yAxis, xMs, middle);

        // ── 레이블 ──
        int lastValid = -1;
        for (int i = upper.length - 1; i >= 0; i--) {
            if (!Double.isNaN(upper[i])) { lastValid = i; break; }
        }
        if (lastValid >= 0) {
            int px = (int) xAxis.valueToJava2D(xMs[lastValid],    plotArea, plot.getDomainAxisEdge());
            int py = (int) yAxis.valueToJava2D(upper[lastValid],   plotArea, plot.getRangeAxisEdge());
            g2.setColor(bandColor);
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 10));
            g2.drawString("BB", px + 3, py - 3);
        }
    }

    /** NaN 건너뛰며 연결선만 그리는 헬퍼 */
    private void drawSimpleLine(Graphics2D g2, Rectangle2D plotArea,
                                 DateAxis xAxis, NumberAxis yAxis,
                                 double[] xMs, double[] values) {
        int prevX = -1, prevY = -1;
        for (int i = 0; i < values.length; i++) {
            if (Double.isNaN(values[i])) { prevX = prevY = -1; continue; }
            int px = (int) xAxis.valueToJava2D(xMs[i],     plotArea, plot.getDomainAxisEdge());
            int py = (int) yAxis.valueToJava2D(values[i],  plotArea, plot.getRangeAxisEdge());
            if (prevX >= 0) g2.drawLine(prevX, prevY, px, py);
            prevX = px;
            prevY = py;
        }
    }

    /**
     * 거래량 오버레이 — 차트 하단 VOLUME_PANEL_RATIO 영역에 막대로 표시.
     * 양봉(close≥open)은 빨간색, 음봉은 파란색 (투명도 적용).
     */
    private void drawVolumeOverlay(Graphics2D g2, Rectangle2D plotArea,
                                    DateAxis xAxis, OHLCDataset dataset,
                                    double[] xMs, double[] volumes) {
        if (volumes.length == 0) return;

        // 거래량 패널 영역 계산 (plotArea 하단 18%)
        double panelH  = plotArea.getHeight() * VOLUME_PANEL_RATIO;
        double panelY  = plotArea.getMaxY() - panelH;
        double panelBottom = plotArea.getMaxY();

        // 최대 거래량 (0 제외)
        double maxVol = 0;
        for (double v : volumes) if (v > maxVol) maxVol = v;
        if (maxVol <= 0) return;

        // 캔들 1개 폭(ms) 기준 바 폭 계산
        double candleMs = getCandleIntervalMs(currentTimeframe);
        double barWidthMs = candleMs * 0.6;
        double msPerPx = plotArea.getWidth() /
                ((DateAxis) plot.getDomainAxis()).getRange().getLength();
        int barWidthPx = Math.max(1, (int)(barWidthMs * msPerPx));

        // 구분선
        g2.setColor(new Color(180, 180, 180, 100));
        g2.setStroke(new BasicStroke(0.5f));
        g2.drawLine((int) plotArea.getMinX(), (int) panelY,
                    (int) plotArea.getMaxX(), (int) panelY);

        // 막대 그리기
        int n = dataset.getItemCount(0);
        for (int i = 0; i < n; i++) {
            if (volumes[i] <= 0) continue;

            double open  = dataset.getOpenValue(0, i);
            double close = dataset.getCloseValue(0, i);
            boolean rising = (close >= open);

            int barH = (int)(panelH * (volumes[i] / maxVol));
            int px   = (int) xAxis.valueToJava2D(xMs[i], plotArea, plot.getDomainAxisEdge());
            int bx   = px - barWidthPx / 2;
            int by   = (int)(panelBottom - barH);

            g2.setColor(rising
                    ? new Color(220, 60, 60, 160)
                    : new Color(60, 80, 220, 160));
            g2.fillRect(bx, by, barWidthPx, barH);
        }

        // "VOL" 레이블
        g2.setColor(new Color(120, 120, 120));
        g2.setFont(new Font("Nanum Gothic", Font.BOLD, 10));
        g2.drawString("VOL", (int) plotArea.getMinX() + 4, (int) panelY - 3);
    }


    // ════════════════════════════════════════════════════
    //  관심코인
    // ════════════════════════════════════════════════════

    private void updateWatchlistButtonState() {
        if (currentUserId == null || btnWatchlist == null) return;
        boolean isWatching = watchListDAO.isWatchlist(currentUserId, currentMarket);
        btnWatchlist.setText(isWatching ? "★" : "☆");
    }

    private void toggleWatchlist() {
        if (currentUserId == null) return;
        boolean isWatching = watchListDAO.isWatchlist(currentUserId, currentMarket);
        if (isWatching) {
            watchListDAO.removeWatchlist(currentUserId, currentMarket);
            btnWatchlist.setText("☆");
        } else {
            watchListDAO.addWatchlist(currentUserId, currentMarket);
            btnWatchlist.setText("★");
        }
        if (watchlistListener != null) watchlistListener.run();
    }


    // ════════════════════════════════════════════════════
    //  ★ 뷰포트 페이징 — 전 타임프레임 공통
    // ════════════════════════════════════════════════════

    private int dbUnitOf(int timeframe) {
        return (timeframe == TF_4H) ? TF_1H : timeframe;
    }

    private int resampleRatioOf(int timeframe) {
        return (timeframe == TF_4H) ? (TF_4H / TF_1H) : 1;
    }

    private void resetAllCaches() {
        for (PageCache c : cacheMap.values()) c.reset();
    }

    private void resetCache(int dbUnit) {
        PageCache c = cacheMap.get(dbUnit);
        if (c != null) c.reset();
    }

    private List<CandleDTO> fetchPagedCandles(String market, int dbUnit,
                                               LocalDateTime targetTime) {
        PageCache cache   = cacheMap.get(dbUnit);
        long bufferMs     = (long)(PAGE_BUFFER * resampleRatioOf(currentTimeframe) * getCandleIntervalMs(dbUnit));

        if (plot == null || !cache.isInitialized()) {
            long fetchTo   = (targetTime != null)
                    ? java.sql.Timestamp.valueOf(targetTime).getTime()
                    : System.currentTimeMillis();
            long fetchFrom = fetchTo - bufferMs * 2;
            return doFetchAndCache(cache, market, dbUnit, fetchFrom, fetchTo, targetTime);
        }

        DateAxis axis    = (DateAxis) plot.getDomainAxis();
        double axisLower = axis.getRange().getLowerBound();
        double axisUpper = axis.getRange().getUpperBound();

        if (axisUpper < cache.fromMs || axisLower > cache.toMs) {
            long center    = (long)((axisLower + axisUpper) / 2);
            long fetchFrom = center - bufferMs;
            long fetchTo   = center + bufferMs;
            return doFetchAndCache(cache, market, dbUnit, fetchFrom, fetchTo, targetTime);
        }

        return cache.candles;
    }

    private List<CandleDTO> doFetchAndCache(PageCache cache, String market, int dbUnit,
                                             long fetchFrom, long fetchTo,
                                             LocalDateTime targetTime) {
        ZoneId kst = ZoneId.of("Asia/Seoul");
        LocalDateTime from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fetchFrom), kst);
        LocalDateTime to   = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(fetchTo),   kst);

        List<CandleDTO> result;
        if (targetTime != null) {
            result = candleDAO.getCandlesInRangeHistorical(market, dbUnit, from, to, targetTime);
        } else {
            result = candleDAO.getCandlesInRange(market, dbUnit, from, to);
        }

        cache.candles = result;
        cache.fromMs  = fetchFrom;
        cache.toMs    = fetchTo;
        return result;
    }

    private void checkAndPrefetchIfNeeded(int timeframe) {
        int dbUnit      = dbUnitOf(timeframe);
        PageCache cache = cacheMap.get(dbUnit);
        if (!cache.isInitialized() || cache.prefetchInProgress) return;

        DateAxis axis = (DateAxis) plot.getDomainAxis();
        double lower  = axis.getRange().getLowerBound();
        double upper  = axis.getRange().getUpperBound();
        long cacheLen = cache.toMs - cache.fromMs;

        boolean nearLeft  = lower < cache.fromMs + cacheLen * PREFETCH_THRESHOLD;
        boolean nearRight = upper > cache.toMs   - cacheLen * PREFETCH_THRESHOLD;
        if (!nearLeft && !nearRight) return;

        cache.prefetchInProgress = true;

        long center   = (long)((lower + upper) / 2);
        long bufferMs = (long)(PAGE_BUFFER * resampleRatioOf(timeframe) * getCandleIntervalMs(dbUnit));
        long newFrom  = center - bufferMs;
        long newTo    = center + bufferMs;

        final String        mkt = currentMarket;
        final LocalDateTime tgt = backtestTargetTime;
        final int           tf  = timeframe;

        new Thread(() -> {
            ZoneId kst = ZoneId.of("Asia/Seoul");
            LocalDateTime from = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(newFrom), kst);
            LocalDateTime to   = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(newTo),   kst);

            List<CandleDTO> fresh = (tgt != null)
                    ? candleDAO.getCandlesInRangeHistorical(mkt, dbUnit, from, to, tgt)
                    : candleDAO.getCandlesInRange(mkt, dbUnit, from, to);

            SwingUtilities.invokeLater(() -> {
                cache.candles = fresh;
                cache.fromMs  = newFrom;
                cache.toMs    = newTo;
                cache.prefetchInProgress = false;

                if (currentTimeframe == tf) {
                    OHLCDataset ds = assembleDataset(mkt, tf, fresh, tgt);
                    plot.setDataset(ds);
                    updateYAxisRange(ds);
                    updateCandleWidth();
                    chartPanel.repaint();
                }
            });
        }, "Prefetch-u" + dbUnit).start();
    }

    private OHLCDataset assembleDataset(String market, int timeframe,
                                         List<CandleDTO> raw, LocalDateTime targetTime) {
        purgeGhostsCoveredByDB(raw);
        List<CandleDTO> processed = applyResample(timeframe, raw);

        if (targetTime == null && liveCandle != null) {
            return mergeWithLiveCandle(market, timeframe, new ArrayList<>(processed));
        }
        if (targetTime == null) mergeGhostCandles(processed);
        return processed.isEmpty() ? emptyDataset(market) : buildDataset(market, processed);
    }

    private List<CandleDTO> applyResample(int timeframe, List<CandleDTO> raw) {
        if (timeframe == TF_4H)   return resample4H(raw);
        if (timeframe == TF_1MON) return resampleByMonth(raw);
        return raw;
    }


    // ════════════════════════════════════════════════════
    //  통합 데이터셋 생성
    // ════════════════════════════════════════════════════

    private OHLCDataset createDataset(String market, int timeframe, LocalDateTime targetTime) {
        int dbUnit = dbUnitOf(timeframe);
        List<CandleDTO> raw = fetchPagedCandles(market, dbUnit, targetTime);

        purgeGhostsCoveredByDB(raw);

        if (raw.isEmpty()) return fallbackDataset(market, timeframe, targetTime);

        List<CandleDTO> processed = new ArrayList<>(applyResample(timeframe, raw));

        if (targetTime == null) {
            mergeGhostCandles(processed);
            if (liveCandle != null) return mergeWithLiveCandle(market, timeframe, processed);
        }

        return buildDataset(market, processed);
    }

    private OHLCDataset fallbackDataset(String market, int timeframe, LocalDateTime targetTime) {
        List<CandleDTO> base;
        if (timeframe == TF_1MON) {
            base = fetchPagedCandles(market, TF_1D, targetTime);
            if (!base.isEmpty()) return buildDataset(market, resampleByMonth(base));
            base = fetchPagedCandles(market, TF_1M, targetTime);
        } else {
            base = fetchPagedCandles(market, TF_1M, targetTime);
        }
        if (base.isEmpty()) return emptyDataset(market);
        return buildDataset(market, resampleByTimeframe(base, timeframe));
    }

    private OHLCDataset createDatasetWithLiveCandle(String market, int timeframe) {
        int dbUnit = dbUnitOf(timeframe);
        List<CandleDTO> raw = new ArrayList<>(cacheMap.get(dbUnit).candles);

        purgeGhostsCoveredByDB(raw);

        List<CandleDTO> processed = applyResample(timeframe, raw);

        if (liveCandle == null) {
            mergeGhostCandles(processed);
            return processed.isEmpty() ? emptyDataset(market) : buildDataset(market, processed);
        }
        List<CandleDTO> withGhost = new ArrayList<>(processed);
        mergeGhostCandles(withGhost);
        return mergeWithLiveCandle(market, timeframe, withGhost);
    }

    private OHLCDataset mergeWithLiveCandle(String market, int timeframe,
                                             List<CandleDTO> processed) {
        if (processed.isEmpty()) {
            processed.add(liveCandle);
            return buildDataset(market, processed);
        }

        CandleDTO last = processed.get(processed.size() - 1);

        if (timeframe == TF_1M) {
            if (liveCandleMinuteStart != null) {
                LocalDateTime lastMinute = last.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES);
                if (lastMinute.equals(liveCandleMinuteStart))
                    processed.remove(processed.size() - 1);
            }
            processed.add(liveCandle);
        } else {
            String lastBlock = calcBlockKey(last.getCandleDateTimeKst(), timeframe);
            String liveBlock = calcBlockKey(liveCandle.getCandleDateTimeKst(), timeframe);

            if (lastBlock.equals(liveBlock)) {
                CandleDTO merged = copyCandle(last);
                merged.setTradePrice(liveCandle.getTradePrice());
                merged.setHighPrice(Math.max(last.getHighPrice(), liveCandle.getHighPrice()));
                merged.setLowPrice(Math.min(last.getLowPrice(), liveCandle.getLowPrice()));
                processed.set(processed.size() - 1, merged);
            } else {
                CandleDTO newBlock = copyCandle(liveCandle);
                newBlock.setUnit(timeframe);
                processed.add(newBlock);
            }
        }

        return buildDataset(market, processed);
    }


    // ════════════════════════════════════════════════════
    //  Ghost 캔들 관리
    // ════════════════════════════════════════════════════

    private void addGhostCandle(CandleDTO candle) {
        LocalDateTime newTime = candle.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES);
        ghostCandles.removeIf(g ->
            g.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES).equals(newTime));
        ghostCandles.add(copyCandle(candle));
        while (ghostCandles.size() > MAX_GHOST_CANDLES) ghostCandles.remove(0);
    }

    private void purgeGhostsCoveredByDB(List<CandleDTO> dbCandles) {
        if (dbCandles.isEmpty() || ghostCandles.isEmpty()) return;
        java.util.Set<LocalDateTime> dbTimes = new java.util.HashSet<>();
        for (CandleDTO c : dbCandles)
            dbTimes.add(c.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES));
        ghostCandles.removeIf(g ->
            dbTimes.contains(g.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES)));
    }

    private void mergeGhostCandles(List<CandleDTO> processed) {
        if (ghostCandles.isEmpty() || currentTimeframe != TF_1M) return;

        LocalDateTime liveMinute = liveCandleMinuteStart;

        for (CandleDTO ghost : ghostCandles) {
            LocalDateTime ghostMinute = ghost.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES);
            if (liveMinute != null && ghostMinute.equals(liveMinute)) continue;

            boolean exists = processed.stream().anyMatch(c ->
                c.getCandleDateTimeKst().truncatedTo(ChronoUnit.MINUTES).equals(ghostMinute));
            if (!exists) processed.add(copyCandle(ghost));
        }

        processed.sort((a, b) -> a.getCandleDateTimeKst().compareTo(b.getCandleDateTimeKst()));
    }


    // ════════════════════════════════════════════════════
    //  리샘플링
    // ════════════════════════════════════════════════════

    private List<CandleDTO> resampleByTimeframe(List<CandleDTO> list, int timeframe) {
        if (timeframe == TF_4H)   return resample4H(list);
        if (timeframe == TF_1MON) return resampleByMonth(list);
        return resampleFixedMinutes(list, timeframe);
    }

    private List<CandleDTO> resampleFixedMinutes(List<CandleDTO> src, int blockMinutes) {
        List<CandleDTO> result = new ArrayList<>();
        List<CandleDTO> group  = new ArrayList<>();
        String groupKey = null;

        for (CandleDTO c : src) {
            String key = calcBlockKey(c.getCandleDateTimeKst(), blockMinutes);
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(mergeGroup(group, blockMinutes));
                group.clear();
                groupKey = key;
            }
            group.add(c);
        }
        if (!group.isEmpty()) result.add(mergeGroup(group, blockMinutes));
        return result;
    }

    private List<CandleDTO> resampleByMonth(List<CandleDTO> src) {
        List<CandleDTO> result = new ArrayList<>();
        List<CandleDTO> group  = new ArrayList<>();
        String groupKey = null;

        for (CandleDTO c : src) {
            String key = c.getCandleDateTimeKst().getYear() + "-"
                       + String.format("%02d", c.getCandleDateTimeKst().getMonthValue());
            if (!key.equals(groupKey)) {
                if (!group.isEmpty()) result.add(mergeGroup(group, TF_1MON));
                group.clear();
                groupKey = key;
            }
            group.add(c);
        }
        if (!group.isEmpty()) result.add(mergeGroup(group, TF_1MON));
        return result;
    }

    private CandleDTO mergeGroup(List<CandleDTO> group, int blockMinutes) {
        CandleDTO first = group.get(0);
        CandleDTO last  = group.get(group.size() - 1);

        LocalDateTime kst = first.getCandleDateTimeKst();
        LocalDateTime blockStart;
        if (blockMinutes == TF_1MON) {
            blockStart = kst.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
        } else {
            LocalDateTime KST_EPOCH = LocalDateTime.of(1970, 1, 1, 9, 0);
            long epochMinutes = ChronoUnit.MINUTES.between(KST_EPOCH, kst);
            long blockIdx     = (epochMinutes / blockMinutes) * blockMinutes;
            blockStart = KST_EPOCH.plusMinutes(blockIdx);
        }

        CandleDTO m = new CandleDTO();
        m.setMarket(first.getMarket());
        m.setCandleDateTimeKst(blockStart);
        m.setCandleDateTimeUtc(blockStart.minusHours(9));
        m.setOpeningPrice(first.getOpeningPrice());
        m.setTradePrice(last.getTradePrice());
        m.setHighPrice(group.stream().mapToDouble(CandleDTO::getHighPrice).max().orElse(0));
        m.setLowPrice(group.stream().mapToDouble(CandleDTO::getLowPrice).min().orElse(0));
        m.setCandleAccTradeVolume(group.stream().mapToDouble(CandleDTO::getCandleAccTradeVolume).sum());
        m.setUnit(blockMinutes);
        return m;
    }

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


    // ════════════════════════════════════════════════════
    //  데이터셋 / DTO 유틸리티
    // ════════════════════════════════════════════════════

    private OHLCDataset buildDataset(String market, List<CandleDTO> list) {
        int count = list.size();
        Date[]   date   = new Date[count];
        double[] high   = new double[count], low    = new double[count];
        double[] open   = new double[count], close  = new double[count];
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

    private String calcBlockKey(LocalDateTime kst, int timeframeMinutes) {
        if (timeframeMinutes == TF_1MON)
            return kst.getYear() + "-" + String.format("%02d", kst.getMonthValue());
        long epochMinutes = ChronoUnit.MINUTES.between(LocalDateTime.of(1970, 1, 1, 9, 0), kst);
        return Long.toString(epochMinutes / timeframeMinutes);
    }


    // ════════════════════════════════════════════════════
    //  차트 갱신
    // ════════════════════════════════════════════════════

    private void refreshChart() {
        OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, backtestTargetTime);
        plot.setDataset(dataset);

        String title = backtestTargetTime != null
                ? getCurrentMarketSymbol() + " (Backtesting)"
                : getCurrentMarketSymbol();
        if (lblChartTitle != null) lblChartTitle.setText(title);

        updateXAxisRange(dataset);
        updateYAxisRange(dataset);
        updateCandleWidth();
        applyDateAxisFormat();
        drawCurrentPriceDashLine(dataset);
    }

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
        double candleIntervalMs = getCandleIntervalMs(currentTimeframe);

        domainAxis.setRange(
            firstTick - candleIntervalMs * 0.5,
            lastTick  + candleIntervalMs * 4.5
        );
    }

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
    //  캔들 폭 동적 조정
    // ════════════════════════════════════════════════════

    private static final double CANDLE_WIDTH_RATIO = 0.7;

    private void updateCandleWidth() {
        if (plot == null) return;
        CandlestickRenderer renderer = (CandlestickRenderer) plot.getRenderer();
        if (renderer == null) return;

        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        double rangeMs = domainAxis.getRange().getLength();
        double candleIntervalMs = getCandleIntervalMs(currentTimeframe);
        int visibleCandles = (int) Math.max(1, Math.round(rangeMs / candleIntervalMs));

        Rectangle2D plotArea = chartPanel.getScreenDataArea();
        if (plotArea == null || plotArea.getWidth() <= 0) {
            renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_AVERAGE);
            renderer.setAutoWidthFactor(CANDLE_WIDTH_RATIO);
            return;
        }

        double pixelsPerCandle = plotArea.getWidth() / visibleCandles;
        double msPerPixel = rangeMs / plotArea.getWidth();
        double widthMs = msPerPixel * pixelsPerCandle * CANDLE_WIDTH_RATIO;

        renderer.setAutoWidthMethod(CandlestickRenderer.WIDTHMETHOD_SMALLEST);
        renderer.setMaxCandleWidthInMilliseconds(widthMs);
    }

    private DecimalFormat buildPriceFormat() {
        return new DecimalFormat("#,##0.##########");
    }

    private String formatPrice(double price) {
        return buildPriceFormat().format(price);
    }


    // ════════════════════════════════════════════════════
    //  가격 박스 오버레이 + 보조지표 렌더링 (OverlayChartPanel)
    // ════════════════════════════════════════════════════

    private class OverlayChartPanel extends ChartPanel {

        OverlayChartPanel(JFreeChart chart) { super(chart); }

        @Override
        public void paintComponent(Graphics g) {
            super.paintComponent(g);

            Rectangle2D plotArea = getScreenDataArea();
            if (plotArea == null || plot == null) return;

            Graphics2D g2 = (Graphics2D) g.create();
            try {
                // ★ 보조지표 렌더링 (캔들 위에 오버레이)
                drawIndicators(g2, plotArea);

                // ★ 현재가 박스
                if (!Double.isNaN(overlayPrice)) {
                    drawPriceBox(g2, plotArea);
                }
            } finally {
                g2.dispose();
            }
        }

        private void drawPriceBox(Graphics2D g2, Rectangle2D plotArea) {
            NumberAxis yAxis = (NumberAxis) plot.getRangeAxis();
            double yPixel = yAxis.valueToJava2D(overlayPrice, plotArea, plot.getRangeAxisEdge());

            int boxW = 110, boxH = 20;
            int boxX = (int) plotArea.getMaxX() + 2;
            int boxY = (int) (yPixel - boxH / 2.0);

            if (boxY < 0 || boxY + boxH > getHeight()) return;

            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(overlayRising ? Color.RED : Color.BLUE);
            g2.fillRoundRect(boxX, boxY, boxW, boxH, 4, 4);

            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Nanum Gothic", Font.BOLD, 12));
            String text = formatPrice(overlayPrice);
            FontMetrics fm = g2.getFontMetrics();
            int textW = fm.stringWidth(text);
            if (textW + 10 > boxW) {
                g2.setColor(overlayRising ? Color.RED : Color.BLUE);
                g2.fillRoundRect(boxX, boxY, textW + 10, boxH, 4, 4);
                g2.setColor(Color.WHITE);
            }
            g2.drawString(text, boxX + 5, boxY + (boxH + fm.getAscent() - fm.getDescent()) / 2);
        }
    }


    // ════════════════════════════════════════════════════
    //  줌
    // ════════════════════════════════════════════════════

    private void handleFixedRightZoom(int wheelRotation) {
        DateAxis domainAxis = (DateAxis) plot.getDomainAxis();
        OHLCDataset dataset = (OHLCDataset) plot.getDataset();
        if (dataset.getItemCount(0) <= 1) return;

        double candleMs  = getCandleIntervalMs(currentTimeframe);
        double upper     = domainAxis.getRange().getUpperBound();
        double length    = domainAxis.getRange().getLength();
        int visibleCount = (int) Math.round(length / candleMs);

        if (wheelRotation > 0) visibleCount = Math.min(MAX_VISIBLE_CANDLES, visibleCount + 5);
        else                   visibleCount = Math.max(MIN_VISIBLE_CANDLES, visibleCount - 5);

        double newLen = visibleCount * candleMs;
        domainAxis.setRange(upper - newLen, upper);

        updateCandleWidth();
        updateYAxisRange(dataset);
        checkAndPrefetchIfNeeded(currentTimeframe);
        chartPanel.repaint();
    }


    // ════════════════════════════════════════════════════
    //  라이브 캔들
    // ════════════════════════════════════════════════════

    private void startLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
        liveTimer = new Timer(LIVE_RENDER_MS, e -> onLiveTick());
        liveTimer.start();
    }

    public void stopLiveTimer() {
        if (liveTimer != null) liveTimer.stop();
    }

    private void onLiveTick() {
        if (backtestTargetTime != null || latestLivePrice <= 0) return;

        LocalDateTime serverNow;
        if (latestLiveTimestamp > 0) {
            serverNow = LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(latestLiveTimestamp),
                    ZoneId.of("Asia/Seoul"));
        } else {
            serverNow = LocalDateTime.now();
        }

        LocalDateTime currentMinuteStart = serverNow.truncatedTo(ChronoUnit.MINUTES);

        if (liveCandleMinuteStart == null || !liveCandleMinuteStart.equals(currentMinuteStart)) {
            if (liveCandle != null && liveCandleMinuteStart != null) addGhostCandle(liveCandle);
            liveCandleMinuteStart = currentMinuteStart;
            liveCandle = createNewLiveCandle(latestLivePrice, currentMinuteStart);
        }

        double price = latestLivePrice;
        liveCandle.setTradePrice(price);
        liveCandle.setHighPrice(Math.max(liveCandle.getHighPrice(), price));
        liveCandle.setLowPrice(Math.min(liveCandle.getLowPrice(), price));

        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDatasetWithLiveCandle(currentMarket, currentTimeframe);
            plot.setDataset(dataset);
            updateYAxisRange(dataset);
            drawCurrentPriceDashLine(dataset);
        });
    }

    public void setLatestPriceFromWebSocket(double price, long serverTimestamp) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice     = price;
        this.latestLiveTimestamp = serverTimestamp;
    }

    public void setLatestPriceFromWebSocket(double price) {
        if (backtestTargetTime != null) return;
        this.latestLivePrice = price;
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


    // ════════════════════════════════════════════════════
    //  공개 API
    // ════════════════════════════════════════════════════

    public void changeMarket(String coinSymbol) {
        this.currentMarket = "KRW-" + coinSymbol;
        liveCandle = null;
        liveCandleMinuteStart = null;
        ghostCandles.clear();
        this.latestLivePrice     = -1;
        this.latestLiveTimestamp = -1;
        if (lblChartTitle != null) lblChartTitle.setText(coinSymbol);

        resetAllCaches();
        refreshChart();
        updateWatchlistButtonState();
        if (backtestTargetTime == null) connectWebSocket();
    }

    public void loadHistoricalData(LocalDateTime targetTime) {
        stopLiveTimer();
        disconnectWebSocket();
        liveCandle = null;
        liveCandleMinuteStart = null;
        resetAllCaches();
        this.backtestTargetTime = targetTime;

        SwingUtilities.invokeLater(() -> {
            OHLCDataset dataset = createDataset(currentMarket, currentTimeframe, targetTime);
            plot.setDataset(dataset);
            updateXAxisRange(dataset);
            updateYAxisRange(dataset);
            updateCandleWidth();
            drawCurrentPriceDashLine(dataset);
            applyDateAxisFormat();
            if (lblChartTitle != null)
                lblChartTitle.setText(getCurrentMarketSymbol() + " (Backtesting)");
        });
    }

    public void resetToRealtimeMode() {
        this.backtestTargetTime = null;
        liveCandle = null;
        liveCandleMinuteStart = null;
        ghostCandles.clear();
        resetAllCaches();
        startLiveTimer();
        connectWebSocket();
        refreshChart();
    }

    private String getCurrentMarketSymbol() {
        return currentMarket.replace("KRW-", "");
    }
}