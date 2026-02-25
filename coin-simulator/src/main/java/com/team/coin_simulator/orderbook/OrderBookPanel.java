package com.team.coin_simulator.orderbook;

import com.team.coin_simulator.backtest.BacktestSpeed;
import com.team.coin_simulator.backtest.BacktestTimeController;
import com.team.coin_simulator.chart.CandleDAO;
import com.team.coin_simulator.chart.CandleDTO;

import java.awt.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;

public class OrderBookPanel extends JPanel
        implements BacktestTimeController.BacktestTickListener {

    // ── UI ──────────────────────────────────────────
    private DefaultTableModel askModel;
    private DefaultTableModel bidModel;
    private JTable askTable;
    private JTable bidTable;
    private JScrollPane scrollPane;
    private boolean isFirstUpdate = true;

    // ── 공통 상태 ────────────────────────────────────
    private String coinSymbol;
    private WebSocket webSocket;

    // ── 백테스팅 전용 상태 ────────────────────────────
    private volatile boolean isBacktestMode = false;

    /**
     * 백테스팅 호가 장부 (분 갱신 이후에도 주문에 의해 물량이 변동됨)
     * 접근은 항상 SwingUtilities.invokeLater 또는 EDT 내에서만 수행
     */
    private final TreeMap<Double, Double> backtestAsks =
            new TreeMap<>();                          // 오름차순 (낮은 ask → 높은 ask)
    private final TreeMap<Double, Double> backtestBids =
            new TreeMap<>(Collections.reverseOrder()); // 내림차순 (높은 bid → 낮은 bid)

    /** 마지막으로 갱신된 시뮬레이션 분(minute-of-day × day) — 분이 바뀔 때만 DB 조회 */
    private int lastUpdatedMinuteKey = -1;

    /** 이전 캔들 종가 (변동률 계산용) */
    private volatile double lastPrevClosePrice = 0;
    /** 현재 캔들 종가 (같은 가격이면 재생성 스킵) */
    private volatile double lastClosePrice = 0;

    private final CandleDAO candleDAO = new CandleDAO();

    // ════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════

    public OrderBookPanel(String coinSymbol) {
        this.coinSymbol = coinSymbol;
        setLayout(new BorderLayout());
        initComponents();

        // 헤더 스타일
        javax.swing.table.JTableHeader tableHeader = askTable.getTableHeader();
        tableHeader.setBackground(Color.WHITE);
        tableHeader.setBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        add(tableHeader, BorderLayout.NORTH);

        // 실시간 WebSocket 연결
        connectUpbit();
    }

    // ════════════════════════════════════════════════
    //  UI 초기화
    // ════════════════════════════════════════════════

    private void initComponents() {
        String[] columns = {"Price (Change %)", "Quantity (" + coinSymbol + ")"};

        askModel = new DefaultTableModel(columns, 0);
        askTable = createStyledTable(askModel,
                new Color(240, 248, 255), new Color(20, 20, 255));

        bidModel = new DefaultTableModel(columns, 0);
        bidTable = createStyledTable(bidModel,
                new Color(255, 245, 245), new Color(255, 20, 20));
        bidTable.setTableHeader(null);

        JPanel container = new JPanel();
        container.setLayout(new BoxLayout(container, BoxLayout.Y_AXIS));
        container.setBackground(Color.WHITE);
        container.add(askTable);
        container.add(bidTable);

        scrollPane = new JScrollPane(container);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);

        add(scrollPane, BorderLayout.CENTER);
    }

    private JTable createStyledTable(DefaultTableModel model,
                                     Color bgColor, Color fgColor) {
        JTable table = new JTable(model) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        table.setRowHeight(30);
        table.setBackground(bgColor);
        table.setForeground(fgColor);
        table.setFont(new Font("SansSerif", Font.BOLD, 13));
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));

        DefaultTableCellRenderer center = new DefaultTableCellRenderer();
        center.setHorizontalAlignment(JLabel.CENTER);
        table.getColumnModel().getColumn(0).setCellRenderer(center);
        table.getColumnModel().getColumn(1).setCellRenderer(center);

        return table;
    }

    // ════════════════════════════════════════════════
    //  실시간 WebSocket
    // ════════════════════════════════════════════════

    private void connectUpbit() {
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url("wss://api.upbit.com/websocket/v1").build();
        String market = "KRW-" + coinSymbol;
        UpbitOrderBookService listener = new UpbitOrderBookService(this, market);
        this.webSocket = client.newWebSocket(request, listener);
    }

    /** 패널 제거 시 WebSocket 종료 */
    public void closeConnection() {
        if (webSocket != null) {
            webSocket.close(1000, "Panel Closed");
            webSocket = null;
        }
    }

    // ════════════════════════════════════════════════
    //  모드 전환 API (MainFrame에서 호출)
    // ════════════════════════════════════════════════

    /**
     * 백테스팅 모드로 전환합니다.
     * 실시간 WebSocket을 종료하고, BacktestTimeController 틱 리스너로 등록됩니다.
     *
     * @param coinSymbol 현재 선택된 코인 심볼 (예: "BTC")
     */
    public void switchToBacktestMode(String coinSymbol) {
        this.coinSymbol = coinSymbol;
        isBacktestMode  = true;
        lastUpdatedMinuteKey = -1;
        lastClosePrice       = 0;

        // 실시간 WebSocket 종료
        closeConnection();

        // 백테스트 틱 리스너 등록
        BacktestTimeController.getInstance().addTickListener(this);

        System.out.println("[OrderBookPanel] 백테스팅 모드 전환: KRW-" + coinSymbol);
    }

    /**
     * 실시간 모드로 복귀합니다.
     * 틱 리스너를 해제하고, 실시간 WebSocket을 재연결합니다.
     *
     * @param coinSymbol 현재 선택된 코인 심볼
     */
    public void switchToRealtimeMode(String coinSymbol) {
        isBacktestMode = false;
        BacktestTimeController.getInstance().removeTickListener(this);

        // 백테스팅 장부 초기화
        SwingUtilities.invokeLater(() -> {
            backtestAsks.clear();
            backtestBids.clear();
        });

        lastUpdatedMinuteKey = -1;
        lastClosePrice       = 0;
        this.coinSymbol      = coinSymbol;
        isFirstUpdate        = true;

        // 실시간 WebSocket 재연결
        connectUpbit();

        System.out.println("[OrderBookPanel] 실시간 모드 복귀: KRW-" + coinSymbol);
    }

    // ════════════════════════════════════════════════
    //  BacktestTickListener 구현
    // ════════════════════════════════════════════════

    /**
     * BacktestTimeController가 매 tick(1초)마다 호출합니다.
     * 시뮬레이션 분(minute)이 바뀔 때만 DB 조회 후 호가창을 재생성합니다.
     */
    @Override
    public void onTick(LocalDateTime currentSimTime, BacktestSpeed speed) {
        if (!isBacktestMode) return;

        // 분 단위 키: 날짜 × 분 → 하루 내 분이 같아도 날짜가 다르면 다른 키
        int minuteKey = currentSimTime.getDayOfYear() * 1440
                      + currentSimTime.getHour()      * 60
                      + currentSimTime.getMinute();

        if (minuteKey == lastUpdatedMinuteKey) return; // 같은 분이면 스킵
        lastUpdatedMinuteKey = minuteKey;

        // DB 조회는 백그라운드 스레드에서 수행 (EDT 블로킹 방지)
        Thread.ofVirtual().start(() -> refreshBacktestOrderBook(currentSimTime));
    }

    // ════════════════════════════════════════════════
    //  백테스팅 호가창 갱신 (DB → 생성 → 표시)
    // ════════════════════════════════════════════════

    private void refreshBacktestOrderBook(LocalDateTime simTime) {
        String market = "KRW-" + coinSymbol;

        // 현재 시각 이전 1분봉 2개 조회 (현재 종가 + 직전 종가)
        List<CandleDTO> candles =
                candleDAO.getHistoricalCandles(market, 1, simTime, 2);

        if (candles == null || candles.isEmpty()) {
            System.out.println("[OrderBookPanel] 캔들 없음: " + market + " @ " + simTime);
            return;
        }

        double closePrice = candles.get(0).getTradePrice();
        double prevClose  = candles.size() >= 2
                ? candles.get(1).getTradePrice()
                : closePrice;

        // 종가가 동일하면 호가 가격 구조는 그대로 → 기존 물량 유지
        if (closePrice == lastClosePrice) return;

        lastClosePrice      = closePrice;
        lastPrevClosePrice  = prevClose;

        // EDT에서 장부 재생성 후 화면 갱신
        SwingUtilities.invokeLater(() -> {
            generateOrderBook(closePrice);
            updateData(backtestAsks, backtestBids, prevClose);
        });
    }

    // ════════════════════════════════════════════════
    //  호가창 생성 로직 (EDT에서 호출)
    // ════════════════════════════════════════════════

    /**
     * 종가 기준으로 매도 15개 / 매수 15개 호가 레벨을 생성합니다.
     *
     * 매도(ask): closePrice + 1 tick  ~  closePrice + 15 tick
     * 매수(bid): closePrice           ~  closePrice - 14 tick
     *
     * 물량은 스프레드에 가까울수록 많고 멀어질수록 감소하는 패턴을 사용합니다.
     * 동일 종가라면 매번 동일한 물량이 재현됩니다(결정론적).
     */
    private void generateOrderBook(double closePrice) {
        backtestAsks.clear();
        backtestBids.clear();

        double tick = getTickSize(closePrice);

        // 매도 15개 (ask): 낮은 가격부터 위로
        for (int i = 1; i <= 15; i++) {
            double price  = roundToTick(closePrice + tick * i, tick);
            double volume = generateVolume(closePrice, i - 1, true);
            backtestAsks.put(price, volume);
        }

        // 매수 15개 (bid): 높은 가격(closePrice)부터 아래로
        for (int i = 0; i <= 14; i++) {
            double price  = roundToTick(closePrice - tick * i, tick);
            double volume = generateVolume(closePrice, i, false);
            backtestBids.put(price, volume);
        }
    }

    /**
     * 결정론적 물량 생성.
     * 같은 closePrice + level 조합이면 항상 동일한 값을 반환합니다.
     *
     * @param basePrice 기준 종가
     * @param level     0 = 스프레드에 가장 가까운 레벨
     * @param isAsk     true = 매도, false = 매수
     */
    private double generateVolume(double basePrice, int level, boolean isAsk) {
        // 스프레드 근처일수록 물량이 많은 가중치
        double[] weights = {
            2.5, 2.2, 1.9, 1.6, 1.4,
            1.2, 1.0, 0.9, 0.8, 0.7,
            0.6, 0.5, 0.4, 0.35, 0.3
        };
        double weight = level < weights.length ? weights[level] : 0.3;

        // 가격대별 기준 물량 (거래소 특성 반영)
        double baseVolume;
        if      (basePrice >= 50_000_000) baseVolume = 0.05;   // BTC
        else if (basePrice >= 10_000_000) baseVolume = 0.2;    // 고가 알트
        else if (basePrice >=  1_000_000) baseVolume = 0.8;    // ETH급
        else if (basePrice >=    100_000) baseVolume = 8.0;
        else if (basePrice >=     10_000) baseVolume = 80.0;
        else if (basePrice >=      1_000) baseVolume = 500.0;
        else                              baseVolume = 3000.0;

        // 결정론적 jitter: 0.80 ~ 1.20 범위
        long seed = (long)(basePrice * 1000) + level * 37L + (isAsk ? 9999L : 0L);
        double jitter = new Random(seed).nextDouble() * 0.4 + 0.8;

        return Math.max(0.0001, baseVolume * weight * jitter);
    }

    // ════════════════════════════════════════════════
    //  주문 발생 시 물량 조정 (OrderPanel → 이 메서드 호출)
    // ════════════════════════════════════════════════

    /**
     * 백테스팅 중 주문 체결/취소 시 호가창 물량을 즉시 반영합니다.
     * 실시간 모드에서는 아무 동작도 하지 않습니다.
     *
     * @param price       대상 가격
     * @param deltaVolume 양수 = 물량 증가, 음수 = 물량 감소
     * @param isAsk       true = 매도 호가, false = 매수 호가
     */
    public void updateQuantityForOrder(double price,
                                       double deltaVolume,
                                       boolean isAsk) {
        if (!isBacktestMode) return;

        SwingUtilities.invokeLater(() -> {
            TreeMap<Double, Double> book = isAsk ? backtestAsks : backtestBids;

            double currentVol = book.getOrDefault(price, 0.0);
            double newVol     = currentVol + deltaVolume;

            if (newVol <= 0.0) {
                book.remove(price);
            } else {
                book.put(price, newVol);
            }

            // 화면 즉시 반영
            updateData(
                new TreeMap<>(backtestAsks),
                new TreeMap<>(backtestBids),
                lastPrevClosePrice
            );
        });
    }

    // ════════════════════════════════════════════════
    //  공용 데이터 표시 (실시간 & 백테스팅 공통)
    // ════════════════════════════════════════════════

    public void updateData(TreeMap<Double, Double> asks,
                           TreeMap<Double, Double> bids,
                           double prevClose) {
        SwingUtilities.invokeLater(() -> {
            askModel.setRowCount(0);
            bidModel.setRowCount(0);

            // 매도: 높은 가격부터 내림차순
            asks.entrySet().stream()
                .limit(15)
                .sorted(Collections.reverseOrder(Map.Entry.comparingByKey()))
                .forEach(e -> addOrderRow(askModel, e.getKey(), e.getValue(), prevClose));

            // 매수: 높은 가격부터 내림차순 (TreeMap이 이미 reverseOrder)
            bids.entrySet().stream()
                .limit(15)
                .forEach(e -> addOrderRow(bidModel, e.getKey(), e.getValue(), prevClose));

            // 첫 로딩 시 스크롤 중앙 정렬
            if (isFirstUpdate && askModel.getRowCount() > 0) {
                SwingUtilities.invokeLater(() -> {
                    int boundaryY      = askTable.getHeight();
                    int viewportHeight = scrollPane.getViewport().getHeight();
                    scrollPane.getVerticalScrollBar()
                              .setValue(boundaryY - viewportHeight / 2);
                    isFirstUpdate = false;
                });
            }
        });
    }

    private void addOrderRow(DefaultTableModel model,
                             double price,
                             double volume,
                             double prevClose) {
        double changeRate = prevClose > 0
                ? ((price - prevClose) / prevClose) * 100
                : 0.0;
        String priceStr = String.format("%,.0f (%.2f%%)", price, changeRate);
        model.addRow(new Object[]{priceStr, String.format("%.4f", volume)});
    }

    // ════════════════════════════════════════════════
    //  업비트 호가 단위 (KRW 마켓 기준)
    // ════════════════════════════════════════════════

    private double getTickSize(double price) {
        if (price >= 2_000_000) return 1_000;
        if (price >= 1_000_000) return   500;
        if (price >=   500_000) return   100;
        if (price >=   100_000) return    50;
        if (price >=    10_000) return    10;
        if (price >=     1_000) return     5;
        if (price >=       100) return     1;
        if (price >=        10) return   0.1;
        if (price >=         1) return  0.01;
        return 0.001;
    }

    private double roundToTick(double price, double tickSize) {
        return Math.round(price / tickSize) * tickSize;
    }

    // ════════════════════════════════════════════════
    //  Getter
    // ════════════════════════════════════════════════

    public boolean isBacktestMode() { return isBacktestMode; }
}
