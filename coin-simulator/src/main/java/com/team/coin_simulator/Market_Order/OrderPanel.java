package com.team.coin_simulator.Market_Order;

import DAO.*;
import DTO.*;
import com.team.coin_simulator.CoinConfig; 
import com.team.coin_simulator.Market_Panel.*;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.List;

public class OrderPanel extends JPanel implements UpbitWebSocketDao.TickerListener {
    
    private OrderDAO orderDAO = new OrderDAO();
    private AssetDAO assetDAO = new AssetDAO(); // 💡 진짜 자산을 불러올 DAO 추가
    private OpenOrderDAO openOrderDAO = new OpenOrderDAO();
    
 // OrderPanel 필드
    private java.util.concurrent.ScheduledExecutorService scheduler;
    
    // 💡 가짜(mock) 데이터 삭제하고, DB에서 불러온 진짜 잔고를 담을 맵
    private Map<String, BigDecimal> realBalance = new HashMap<>();
    private Map<String, BigDecimal> realLocked = new HashMap<>();
    
    private List<OrderDTO> openOrders = new ArrayList<>();
    private Map<Long, String> orderCoinMap = new HashMap<>();

    private CardLayout cardLayout;
    private JPanel inputCardPanel;
    private JTextField priceField, qtyField, marketAmountField;
    private JLabel valAvailable, valTotal, valExpected, lblSelectedCoinInfo, lblMarketUnit;
    private JButton btnAction;
    
    // 💡 [master 장점] 분리된 주문 정정 패널 도입
    private OrderEditListPanel orderEditListPanel; 

    private String userId;
    private long sessionId = 1L; // 💡 현재 세션 ID 필드 유지
    
    private String selectedCoinCode = "BTC"; 
    private BigDecimal currentSelectedPrice = BigDecimal.ZERO; 
    private int sideIdx = 0;
    private boolean isLimitMode = true;
    private Map<String, BigDecimal> latestPrices = new java.util.concurrent.ConcurrentHashMap<>();

    private final Color COLOR_BID = new Color(200, 30, 30);
    private final Color COLOR_ASK = new Color(30, 70, 200);
    
    public OrderPanel(String userId) {
        this.userId = userId;
        
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        setPreferredSize(new Dimension(350, 600));

        // 상단 탭
        JPanel topTabPanel = new JPanel(new GridLayout(1, 3));
        topTabPanel.setBackground(Color.WHITE);
        TabButton btnBid = new TabButton("매수");
        TabButton btnAsk = new TabButton("매도");
        TabButton btnEdit = new TabButton("주문정정");
        btnBid.setSelected(true);
        topTabPanel.add(btnBid); topTabPanel.add(btnAsk); topTabPanel.add(btnEdit);
        add(topTabPanel, BorderLayout.NORTH);

        cardLayout = new CardLayout();
        inputCardPanel = new JPanel(cardLayout);

        // 1. 매수/매도 입력 패널
        JPanel tradePanel = createTradePanel();

        // 2. 분리한 주문 정정/취소 리스트 패널 장착 (콜백으로 refreshDBData 전달)
        orderEditListPanel = new OrderEditListPanel(this.userId, this::refreshDBData);

        inputCardPanel.add(tradePanel, "TRADE");
        inputCardPanel.add(orderEditListPanel, "EDIT");

        add(inputCardPanel, BorderLayout.CENTER);
        
        // 탭 전환 이벤트
        btnBid.addActionListener(e -> { switchSide(0, btnBid, btnAsk, btnEdit); cardLayout.show(inputCardPanel, "TRADE"); });
        btnAsk.addActionListener(e -> { switchSide(1, btnAsk, btnBid, btnEdit); cardLayout.show(inputCardPanel, "TRADE"); });
        btnEdit.addActionListener(e -> { switchSide(-1, btnEdit, btnBid, btnAsk); cardLayout.show(inputCardPanel, "EDIT"); });

        // 초기 데이터 로드 및 웹소켓 시작
        refreshDBData();
        UpbitWebSocketDao.getInstance().addListener(this);
    }

    private JPanel createTradePanel() {
        JPanel tradePanel = new JPanel();
        tradePanel.setLayout(new BoxLayout(tradePanel, BoxLayout.Y_AXIS));
        tradePanel.setBorder(BorderFactory.createEmptyBorder(15, 15, 15, 15));
        tradePanel.setBackground(Color.WHITE);

        lblSelectedCoinInfo = new JLabel("비트코인 (BTC)");
        lblSelectedCoinInfo.setFont(new Font("맑은 고딕", Font.BOLD, 16));
        lblSelectedCoinInfo.setAlignmentX(Component.CENTER_ALIGNMENT);
        tradePanel.add(lblSelectedCoinInfo);
        tradePanel.add(Box.createVerticalStrut(10));

        JPanel modePanel = new JPanel(new GridLayout(1, 2, 5, 0));
        modePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        TabButton btnLimit = new TabButton("지정가");
        TabButton btnMarket = new TabButton("시장가");
        btnLimit.setSelected(true);
        modePanel.add(btnLimit); modePanel.add(btnMarket);
        tradePanel.add(modePanel); tradePanel.add(Box.createVerticalStrut(20));

        CardLayout tradeCardLayout = new CardLayout();
        JPanel tradeInputPanel = new JPanel(tradeCardLayout);
        tradeInputPanel.add(createLimitForm(), "LIMIT");
        tradeInputPanel.add(createMarketForm(), "MARKET");
        tradePanel.add(tradeInputPanel);
        
        tradePanel.add(Box.createVerticalGlue());

        // 하단 정보 및 버튼
        Dimension btnSize = new Dimension(340, 50);
        JPanel infoContainer = new JPanel();
        infoContainer.setLayout(new BoxLayout(infoContainer, BoxLayout.Y_AXIS));
        infoContainer.setBackground(Color.WHITE);
        infoContainer.setMaximumSize(btnSize);

        JPanel availRow = new JPanel(new BorderLayout()); availRow.setBackground(Color.WHITE);
        availRow.add(new JLabel("주문 가능"), BorderLayout.WEST);
        valAvailable = new JLabel("0 KRW"); valAvailable.setHorizontalAlignment(SwingConstants.RIGHT);
        availRow.add(valAvailable, BorderLayout.CENTER);

        JPanel totalRow = new JPanel(new BorderLayout()); totalRow.setBackground(Color.WHITE);
        totalRow.add(new JLabel("주문 총액"), BorderLayout.WEST);
        valTotal = new JLabel("0.00 KRW"); valTotal.setHorizontalAlignment(SwingConstants.RIGHT);
        totalRow.add(valTotal, BorderLayout.CENTER);

        infoContainer.add(availRow); infoContainer.add(Box.createVerticalStrut(10));
        infoContainer.add(totalRow); infoContainer.add(Box.createVerticalStrut(20));

        // [SuBackT 장점] 더 세련된 커스텀 페인팅 버튼 유지
        btnAction = new JButton("매수") {
            @Override
            protected void paintComponent(Graphics g) {
                if (getModel().isArmed()) g.setColor(getBackground().darker());
                else g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                super.paintComponent(g);
            }
        };
        btnAction.setBackground(COLOR_BID); btnAction.setForeground(Color.WHITE);
        btnAction.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        btnAction.setContentAreaFilled(false); btnAction.setOpaque(false); 
        btnAction.setFocusPainted(false);
        btnAction.setPreferredSize(btnSize); btnAction.setMinimumSize(btnSize); btnAction.setMaximumSize(btnSize);
        btnAction.setAlignmentX(Component.CENTER_ALIGNMENT);
        btnAction.addActionListener(e -> handleOrderAction());

        tradePanel.add(infoContainer); tradePanel.add(btnAction);

        btnLimit.addActionListener(e -> { isLimitMode = true; btnLimit.setSelected(true); btnMarket.setSelected(false); tradeCardLayout.show(tradeInputPanel, "LIMIT"); updateOrderSummary(); });
        btnMarket.addActionListener(e -> { isLimitMode = false; btnMarket.setSelected(true); btnLimit.setSelected(false); tradeCardLayout.show(tradeInputPanel, "MARKET"); updateMarketCalculation(); });

        DocumentListener updateListener = new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateOrderSummary(); }
            public void removeUpdate(DocumentEvent e) { updateOrderSummary(); }
            public void changedUpdate(DocumentEvent e) { updateOrderSummary(); }
        };
        priceField.getDocument().addDocumentListener(updateListener);
        if (qtyField != null) qtyField.getDocument().addDocumentListener(updateListener);

        return tradePanel;
    }
    
    // 💡 세션 변경 시 DB에서 잔고 및 미체결 주문 다시 불러오기
    public void setSessionId(long sessionId) {
        this.sessionId = sessionId;
        refreshDBData();
    }
    
 // 자동체결 시작할 때 (예: 생성자/초기화에서)
    private void startAutoExecution() {
        scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AutoExecutionThread");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 기존에 하던 자동체결 로직 호출
                // OrderDAO.checkAndExecuteLimitOrders(...);
            } catch (Exception e) {
                // 로그만
                System.out.println(">> [자동 체결 오류] " + e.getMessage());
            }
        }, 0, 2, java.util.concurrent.TimeUnit.SECONDS); // 주기는 너 기존에 맞춰
    }
    
    // 💡 [핵심] DB와 동기화하는 메서드 (가짜 데이터 대신 진짜 데이터를 읽어옴)
    private void refreshDBData() {
        realBalance.clear();
        realLocked.clear();
        
        // 1. 진짜 잔고 불러오기
        List<AssetDTO> assets = assetDAO.getAllAssets(this.userId, this.sessionId);
        
        System.out.println("\n[🔍 OrderPanel DB 조회 테스트]");
        System.out.println("▶ 조회 요청 ID: " + this.userId);
        System.out.println("▶ 조회 요청 세션방: " + this.sessionId);
        System.out.println("▶ DB에서 가져온 자산 개수: " + assets.size() + "개");
        
        for (AssetDTO a : assets) {
            System.out.println("  - " + a.getCurrency() + " 잔고: " + a.getBalance() + " / 묶인돈(locked): " + a.getLocked());
            realBalance.put(a.getCurrency(), a.getBalance());
            realLocked.put(a.getCurrency(), a.getLocked());
        }
        System.out.println("-----------------------------------\n");
        
        // 2. 이 세션의 미체결 주문 불러오기
        openOrders = openOrderDAO.getOpenOrders(this.userId, this.sessionId);
        orderCoinMap.clear();
        for (OrderDTO o : openOrders) {
            orderCoinMap.put(o.getOrderId(), o.getMarket().replace("KRW-", ""));
        }
        
        SwingUtilities.invokeLater(() -> {
            updateInfoLabel();
            // 💡 master가 만들어둔 외부 패널로 진짜 데이터 밀어넣기!
            if (orderEditListPanel != null) {
                orderEditListPanel.updateData(openOrders, orderCoinMap, realBalance, realLocked);
            }
        });
    }

 // ✅ MainFrame이 호출할 종료 메서드
    public void shutdown() {
        try {
            if (scheduler != null && !scheduler.isShutdown()) {
                scheduler.shutdownNow();
            }
        } catch (Exception ignored) {}
    }
    
    public void setSelectedCoin(String coinSymbol) {
        this.selectedCoinCode = coinSymbol;
        String krName = CoinConfig.COIN_INFO.getOrDefault(coinSymbol, coinSymbol);
        BigDecimal cachedPrice = latestPrices.get(coinSymbol);
        
        if (cachedPrice != null) {
            this.currentSelectedPrice = cachedPrice;
            if (lblSelectedCoinInfo != null) lblSelectedCoinInfo.setText(krName + " - 현재가 " + String.format("%,.0f", cachedPrice) + " KRW");
            if (isLimitMode && priceField != null) priceField.setText(cachedPrice.toPlainString());
            updateOrderSummary();
        } else {
            if (lblSelectedCoinInfo != null) lblSelectedCoinInfo.setText(krName + " (" + coinSymbol + ")");
        }
        switchSide(sideIdx, null, null, null); 
    }

    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr) {
        String cleanPrice = priceStr.replace(",", "").replace(" KRW", "").trim();
        if (cleanPrice.isEmpty() || cleanPrice.equals("연결중...")) return;

        BigDecimal currentPrice = new BigDecimal(cleanPrice);
        latestPrices.put(symbol, currentPrice);

        // [master 장점] 💡 시세 업데이트 시 자동체결 엔진 구동 쓰레드
        new Thread(() -> {
            List<OrderDTO> executedList = orderDAO.checkAndExecuteLimitOrders(symbol, currentPrice);
            if (executedList != null && !executedList.isEmpty()) {
                for (OrderDTO order : executedList) {
                    String typeStr = order.getSide().equals("BID") ? "매수" : "매도";
                    String msg = String.format("[지정가 체결] %s %s 완료!\n(가격: %,.0f KRW, 수량: %s)", 
                            symbol, typeStr, order.getOriginalPrice(), order.getOriginalVolume().toPlainString());
                    
                    SwingUtilities.invokeLater(() -> {
                        JFrame parentFrame = (JFrame) SwingUtilities.getWindowAncestor(OrderPanel.this);
                        if(parentFrame != null) com.team.coin_simulator.Alerts.NotificationUtil.showToast(parentFrame, msg);
                        refreshDBData(); // 체결 성공 시 DB 새로고침!
                    });
                }
            }
        }).start();

        if (!this.selectedCoinCode.equals(symbol)) return;

        this.currentSelectedPrice = currentPrice;
        String krName = com.team.coin_simulator.CoinConfig.COIN_INFO.getOrDefault(symbol, symbol);
        
        SwingUtilities.invokeLater(() -> {
            lblSelectedCoinInfo.setText(krName + " - 현재가 " + String.format("%,.0f", currentSelectedPrice) + " KRW");
            if (isLimitMode && priceField.getText().isEmpty()) {
                priceField.setText(cleanPrice); updateOrderSummary();
            }
            if (!isLimitMode) updateMarketCalculation();
        });
    }

    private void updateInfoLabel() {
        String assetCode = (sideIdx == 0) ? "KRW" : selectedCoinCode; 
        BigDecimal balance = realBalance.getOrDefault(assetCode, BigDecimal.ZERO);
        String format = assetCode.equals("KRW") ? "%,.0f" : "%.8f";
        valAvailable.setText(String.format(format + " %s", balance, assetCode));
    }

    private void updateOrderSummary() {
        updateInfoLabel(); updateMarketCalculation(); 
        try {
            String pStr = priceField.getText().replace(",", "").trim();
            String qStr = qtyField.getText().replace(",", "").trim();
            if (!pStr.isEmpty() && !qStr.isEmpty()) {
                BigDecimal total = OrderCalc.calcTotalCost(new BigDecimal(pStr), new BigDecimal(qStr));
                SwingUtilities.invokeLater(() -> valTotal.setText(String.format("%,.2f KRW", total)));
            } else valTotal.setText("0.00 KRW");
        } catch (Exception e) { valTotal.setText("0.00 KRW"); }
    }

    private void updateMarketCalculation() {
        try {
            String amtStr = marketAmountField.getText().replace(",", "").trim();
            if (currentSelectedPrice.compareTo(BigDecimal.ZERO) <= 0 || amtStr.isEmpty()) { valExpected.setText("-"); return; }
            BigDecimal inputVal = new BigDecimal(amtStr);
            if (sideIdx == 0) { 
                BigDecimal expectedQty = inputVal.divide(currentSelectedPrice, 8, RoundingMode.DOWN);
                valExpected.setText("예상 수량: " + expectedQty.toPlainString() + " " + selectedCoinCode);
            } else { 
                valExpected.setText("예상 수령: " + String.format("%,.0f", inputVal.multiply(currentSelectedPrice)) + " KRW");
            }
        } catch (Exception e) { valExpected.setText("계산 불가"); }
    }

    private void switchSide(int side, TabButton selected, TabButton un1, TabButton un2) {
        this.sideIdx = side;
        if (selected != null) selected.setSelected(true);
        if (un1 != null) un1.setSelected(false);
        if (un2 != null) un2.setSelected(false);
        if (side != -1) {
            btnAction.setText(side == 0 ? "매수" : "매도");
            btnAction.setBackground(side == 0 ? COLOR_BID : COLOR_ASK);
            if (lblMarketUnit != null) {
                if (side == 0) { lblMarketUnit.setText("주문총액 (KRW)"); valExpected.setText("예상 수량: -"); } 
                else { lblMarketUnit.setText("주문수량 (" + selectedCoinCode + ")"); valExpected.setText("예상 수령액: -"); }
                marketAmountField.setText("");
            }
        }
        updateInfoLabel();
    }

    private void handleOrderAction() {
        if (isLimitMode) handleLimitOrder();
        else handleMarketOrder();
    }

    private void handleLimitOrder() {
        try {
            BigDecimal price = new BigDecimal(priceField.getText().replace(",", "").trim());
            BigDecimal qty = new BigDecimal(qtyField.getText().replace(",", "").trim());
            BigDecimal requiredAmount = (sideIdx == 0) ? price.multiply(qty) : qty;
            String currency = (sideIdx == 0) ? "KRW" : selectedCoinCode;
            
            // 💡 진짜 잔고로 결제 여부 검사
            BigDecimal balance = realBalance.getOrDefault(currency, BigDecimal.ZERO);

            if (balance.compareTo(requiredAmount) < 0) {
                throw new RuntimeException("주문 가능 잔고가 부족합니다.");
            }

            OrderDTO order = new OrderDTO();
            order.setOrderId(System.currentTimeMillis());
            order.setUserId(this.userId); 
            order.setSessionId(this.sessionId); 
            order.setMarket("KRW-" + selectedCoinCode); 
            order.setSide(sideIdx == 0 ? "BID" : "ASK");
            order.setOriginalPrice(price);
            order.setOriginalVolume(qty);
            order.setRemainingVolume(qty);
            order.setStatus("WAIT");

            if (orderDAO.insertOrder(order)) {
                JOptionPane.showMessageDialog(this, "지정가 주문 접수 완료");
                refreshDBData(); // 💡 주문 접수 후 DB 새로고침
            } else throw new RuntimeException("데이터베이스 저장에 실패했습니다.");
        } catch (Exception e) { JOptionPane.showMessageDialog(this, "주문 오류: " + e.getMessage(), "알림", JOptionPane.ERROR_MESSAGE); }
    }

    private void handleMarketOrder() {
        try {
            BigDecimal inputVal = new BigDecimal(marketAmountField.getText().replace(",", "").trim());

            if (sideIdx == 0) { 
                BigDecimal krwBal = realBalance.getOrDefault("KRW", BigDecimal.ZERO);
                if (krwBal.compareTo(inputVal) < 0) throw new RuntimeException("KRW 잔고가 부족합니다.");
                
                BigDecimal buyQty = OrderCalc.calculateMarketBuyQuantity(inputVal, currentSelectedPrice);
                OrderDTO marketOrder = new OrderDTO(); 
                marketOrder.setOrderId(System.currentTimeMillis()); 
                marketOrder.setUserId(this.userId); 
                marketOrder.setSessionId(this.sessionId); 
                marketOrder.setMarket("KRW-" + selectedCoinCode); 
                marketOrder.setSide("BID"); 
                marketOrder.setStatus("DONE");
                
                if (orderDAO.executeMarketOrder(marketOrder, this.userId, currentSelectedPrice, buyQty, inputVal)) {
                    com.team.coin_simulator.Alerts.NotificationUtil.showToast((JFrame) SwingUtilities.getWindowAncestor(this), String.format("[체결] %s 시장가 매수 완료 (%.8f개)", selectedCoinCode, buyQty));
                    refreshDBData(); // 💡 DB 동기화
                } else throw new RuntimeException("DB 저장 실패");
            } else { 
                BigDecimal coinBal = realBalance.getOrDefault(selectedCoinCode, BigDecimal.ZERO);
                if (coinBal.compareTo(inputVal) < 0) throw new RuntimeException(selectedCoinCode + " 잔고가 부족합니다.");
                
                BigDecimal sellTotalKRW = inputVal.multiply(currentSelectedPrice);
                OrderDTO marketOrder = new OrderDTO(); 
                marketOrder.setOrderId(System.currentTimeMillis()); 
                marketOrder.setUserId(this.userId); 
                marketOrder.setSessionId(this.sessionId); 
                marketOrder.setMarket("KRW-" + selectedCoinCode); 
                marketOrder.setSide("ASK"); 
                marketOrder.setStatus("DONE");
                
                if (orderDAO.executeMarketOrder(marketOrder, this.userId, currentSelectedPrice, inputVal, sellTotalKRW)) {
                    com.team.coin_simulator.Alerts.NotificationUtil.showToast((JFrame) SwingUtilities.getWindowAncestor(this), String.format("[체결] %s 시장가 매도 완료 (%,.0f KRW)", selectedCoinCode, sellTotalKRW));
                    refreshDBData(); // 💡 DB 동기화
                } else throw new RuntimeException("DB 저장 실패");
            }
            marketAmountField.setText(""); 
        } catch (Exception e) { JOptionPane.showMessageDialog(this, "주문 실패: " + e.getMessage(), "에러", JOptionPane.ERROR_MESSAGE); }
    }

    // 💡 [master 장점] 지정가 폼 (버튼 팩 추가)
    private JPanel createLimitForm() {
        JPanel p = new JPanel(); 
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); 
        p.setBackground(Color.WHITE);
        
        JLabel lblPrice = new JLabel("주문가격 (KRW)"); 
        lblPrice.setAlignmentX(Component.LEFT_ALIGNMENT); 
        p.add(lblPrice);
        
        JPanel priceRow = new JPanel(new BorderLayout()); 
        priceRow.setBackground(Color.WHITE);
        priceRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        
        priceField = new JTextField(); styleField(priceField);
        priceRow.add(priceField, BorderLayout.CENTER);
        
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 0, 0));
        JButton btnMinus = new JButton("-");
        JButton btnPlus = new JButton("+");
        
        Font mathFont = new Font("맑은 고딕", Font.BOLD, 16);
        btnMinus.setFont(mathFont); btnMinus.setBackground(new Color(240, 240, 240)); btnMinus.setFocusPainted(false);
        btnPlus.setFont(mathFont); btnPlus.setBackground(new Color(240, 240, 240)); btnPlus.setFocusPainted(false);
        
        btnMinus.addActionListener(e -> adjustPrice(false));
        btnPlus.addActionListener(e -> adjustPrice(true));
        
        btnPanel.add(btnMinus); btnPanel.add(btnPlus);
        priceRow.add(btnPanel, BorderLayout.EAST);
        
        p.add(priceRow); 
        p.add(Box.createVerticalStrut(10));
        
        JLabel lblQty = new JLabel("주문수량"); 
        lblQty.setAlignmentX(Component.LEFT_ALIGNMENT); 
        p.add(lblQty);
        
        JPanel qtyRow = new JPanel(new BorderLayout()); 
        qtyRow.setBackground(Color.WHITE);
        qtyRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        qtyField = new JTextField(); styleField(qtyField); 
        qtyRow.add(qtyField, BorderLayout.CENTER);
        p.add(qtyRow);
        
        p.add(Box.createVerticalStrut(5));
        p.add(createPercentPanel()); 
        
        return p;
    }

    // 💡 [master 장점] 시장가 폼 (퍼센트 추가)
    private JPanel createMarketForm() {
        JPanel p = new JPanel(); 
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); 
        p.setBackground(Color.WHITE);
        
        lblMarketUnit = new JLabel("주문총액 (KRW)"); 
        lblMarketUnit.setAlignmentX(Component.LEFT_ALIGNMENT); 
        p.add(lblMarketUnit);
        
        JPanel marketRow = new JPanel(new BorderLayout()); 
        marketRow.setBackground(Color.WHITE);
        marketRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        marketAmountField = new JTextField(); styleField(marketAmountField); 
        marketRow.add(marketAmountField, BorderLayout.CENTER);
        p.add(marketRow);
        
        p.add(Box.createVerticalStrut(5));
        p.add(createPercentPanel()); //시장가용 버튼
        
        p.add(Box.createVerticalStrut(10));
        valExpected = new JLabel("예상 수량: -"); 
        valExpected.setForeground(Color.GRAY); 
        valExpected.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(valExpected);
        
        marketAmountField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateMarketCalculation(); }
            public void removeUpdate(DocumentEvent e) { updateMarketCalculation(); }
            public void changedUpdate(DocumentEvent e) { updateMarketCalculation(); }
        });
        
        return p;
    }

    private void styleField(JTextField tf) {
        tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        tf.setHorizontalAlignment(JTextField.RIGHT);
    }

    // 💡 [master 장점] 퍼센트 버튼 패널
    private JPanel createPercentPanel() {
        JPanel p = new JPanel(new GridLayout(1, 5, 5, 0)); 
        p.setBackground(Color.WHITE);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);

        String[] labels = {"10%", "25%", "50%", "100%", "직접"};
        double[] fractions = {0.10, 0.25, 0.50, 1.00, 0.0};

        for (int i = 0; i < labels.length; i++) {
            JButton btn = new JButton(labels[i]);
            btn.setBackground(new Color(245, 245, 245));
            btn.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
            btn.setMargin(new Insets(2, 0, 2, 0));
            btn.setFocusPainted(false);
            
            final double frac = fractions[i];
            btn.addActionListener(e -> applyPercent(frac));
            p.add(btn);
        }
        return p;
    }

    // 💡 [수정] 진짜 잔고(realBalance)를 사용해 퍼센트 자동 입력 기능 적용
    private void applyPercent(double fraction) {
        if (fraction == 0.0) { 
            if (isLimitMode) qtyField.setText("");
            else marketAmountField.setText("");
            updateOrderSummary();
            return;
        }

        try {
            if (isLimitMode) { // [지정가 모드]
                if (sideIdx == 0) { // 매수 (KRW 기준 -> 수량 계산)
                    BigDecimal availKrw = realBalance.getOrDefault("KRW", BigDecimal.ZERO);
                    
                    String pStr = priceField.getText().replace(",", "").trim();
                    if (pStr.isEmpty()) { 
                        if (currentSelectedPrice.compareTo(BigDecimal.ZERO) > 0) {
                            pStr = currentSelectedPrice.toPlainString();
                            priceField.setText(pStr);
                        } else return;
                    }
                    BigDecimal price = new BigDecimal(pStr);
                    BigDecimal qty = OrderCalc.calcPercentLimitBuyQty(availKrw, fraction, price);
                    qtyField.setText(qty.compareTo(BigDecimal.ZERO) == 0 ? "" : qty.toPlainString());
                    
                } else { // 매도 (코인 기준 -> 수량 계산)
                    BigDecimal availCoin = realBalance.getOrDefault(selectedCoinCode, BigDecimal.ZERO);
                    BigDecimal qty = OrderCalc.calcPercentSellQty(availCoin, fraction);
                    qtyField.setText(qty.compareTo(BigDecimal.ZERO) == 0 ? "" : qty.toPlainString());
                }
            } else { // [시장가 모드]
                if (sideIdx == 0) { // 매수 (KRW 기준 -> 총액 계산)
                    BigDecimal availKrw = realBalance.getOrDefault("KRW", BigDecimal.ZERO);
                    BigDecimal amt = OrderCalc.calcPercentMarketBuyAmount(availKrw, fraction);
                    marketAmountField.setText(amt.compareTo(BigDecimal.ZERO) == 0 ? "" : amt.toPlainString());
                    
                } else { // 매도 (코인 기준 -> 수량 계산)
                    BigDecimal availCoin = realBalance.getOrDefault(selectedCoinCode, BigDecimal.ZERO);
                    BigDecimal qty = OrderCalc.calcPercentSellQty(availCoin, fraction);
                    marketAmountField.setText(qty.compareTo(BigDecimal.ZERO) == 0 ? "" : qty.toPlainString());
                }
            }
            updateOrderSummary(); 
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }
    
    // 💡 [master 장점] 가격 [+] [-] 버튼 조작
    private void adjustPrice(boolean isPlus) {
        try {
            String pStr = priceField.getText().replace(",", "").trim();
            BigDecimal currentVal;
            
            if (pStr.isEmpty()) {
                if (currentSelectedPrice.compareTo(BigDecimal.ZERO) <= 0) return;
                currentVal = currentSelectedPrice;
            } else {
                currentVal = new BigDecimal(pStr);
            }

            BigDecimal tickSize = OrderCalc.getTickSize(currentVal);
            
            if (isPlus) {
                currentVal = currentVal.add(tickSize);
            } else {
                currentVal = currentVal.subtract(tickSize);
                if (currentVal.compareTo(BigDecimal.ZERO) <= 0) {
                    currentVal = tickSize; 
                }
            }
            
            priceField.setText(currentVal.stripTrailingZeros().toPlainString());
            updateOrderSummary(); 
            
        } catch (Exception ex) {}
    }
}