package com.team.coin_simulator.Market_Order;

import DAO.*;
import DTO.*;
import com.team.coin_simulator.CoinConfig; 
import com.team.coin_simulator.Market_Panel.*;
import com.team.coin_simulator.DBConnection;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

public class OrderPanel extends JPanel implements UpbitWebSocketDao.TickerListener {
    
    private OrderDAO orderDAO = new OrderDAO();
    private Map<String, BigDecimal> mockBalance = new HashMap<>();
    private Map<String, BigDecimal> mockLocked = new HashMap<>();
    private List<OrderDTO> openOrders = new ArrayList<>();
    private Map<Long, String> orderCoinMap = new HashMap<>();

    private CardLayout cardLayout;
    private JPanel inputCardPanel;
    private JTextField priceField, qtyField, marketAmountField;
    private JLabel valAvailable, valTotal, valExpected, lblSelectedCoinInfo, lblMarketUnit;
    private JButton btnAction;
    private OrderEditListPanel orderEditListPanel; 

    private String userId;
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

        // 2. 분리한 주문 정정/취소 리스트 패널 장착 (콜백으로 loadUserData 전달)
        orderEditListPanel = new OrderEditListPanel(this.userId, this::loadUserData);

        inputCardPanel.add(tradePanel, "TRADE");
        inputCardPanel.add(orderEditListPanel, "EDIT");

        add(inputCardPanel, BorderLayout.CENTER);
        
        // 탭 전환 이벤트
        btnBid.addActionListener(e -> { switchSide(0, btnBid, btnAsk, btnEdit); cardLayout.show(inputCardPanel, "TRADE"); });
        btnAsk.addActionListener(e -> { switchSide(1, btnAsk, btnBid, btnEdit); cardLayout.show(inputCardPanel, "TRADE"); });
        btnEdit.addActionListener(e -> { switchSide(-1, btnEdit, btnBid, btnAsk); cardLayout.show(inputCardPanel, "EDIT"); });

        // 초기 데이터 로드 및 웹소켓 시작
        loadUserData();
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

        btnAction = new JButton("매수");
        btnAction.setBackground(COLOR_BID); btnAction.setForeground(Color.WHITE);
        btnAction.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        btnAction.setContentAreaFilled(false); btnAction.setOpaque(true); 
        btnAction.setPreferredSize(btnSize); btnAction.setMaximumSize(btnSize);
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
        qtyField.getDocument().addDocumentListener(updateListener);

        return tradePanel;
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
                        loadUserData(); // 체결 성공 시 DB 새로고침!
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
        BigDecimal balance = mockBalance.getOrDefault(assetCode, BigDecimal.ZERO);
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
                valExpected.setText("예상 수량: " + inputVal.divide(currentSelectedPrice, 8, BigDecimal.ROUND_DOWN).toPlainString() + " " + selectedCoinCode);
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

            if (mockBalance.getOrDefault(currency, BigDecimal.ZERO).compareTo(requiredAmount) < 0) {
                throw new RuntimeException("주문 가능 잔고가 부족합니다.");
            }

            OrderDTO order = new OrderDTO();
            order.setOrderId(System.currentTimeMillis());
            order.setSide(sideIdx == 0 ? "BID" : "ASK");
            order.setOriginalPrice(price);
            order.setOriginalVolume(qty);
            order.setRemainingVolume(qty);
            order.setStatus("WAIT");

            if (orderDAO.insertOrder(order, this.userId)) {
                JOptionPane.showMessageDialog(this, "지정가 주문 접수 완료");
                loadUserData(); // 주문 접수 후 데이터 새로고침
            } else throw new RuntimeException("데이터베이스 저장에 실패했습니다.");
        } catch (Exception e) { JOptionPane.showMessageDialog(this, "주문 오류: " + e.getMessage(), "알림", JOptionPane.ERROR_MESSAGE); }
    }

    private void handleMarketOrder() {
        try {
            BigDecimal inputVal = new BigDecimal(marketAmountField.getText().replace(",", "").trim());

            if (sideIdx == 0) { 
                if (mockBalance.getOrDefault("KRW", BigDecimal.ZERO).compareTo(inputVal) < 0) throw new RuntimeException("KRW 잔고가 부족합니다.");
                BigDecimal buyQty = OrderCalc.calculateMarketBuyQuantity(inputVal, currentSelectedPrice);
                
                OrderDTO marketOrder = new OrderDTO(); marketOrder.setOrderId(System.currentTimeMillis()); marketOrder.setSide("BID"); marketOrder.setStatus("DONE");
                if (orderDAO.executeMarketOrder(marketOrder, this.userId, currentSelectedPrice, buyQty, inputVal)) {
                    com.team.coin_simulator.Alerts.NotificationUtil.showToast((JFrame) SwingUtilities.getWindowAncestor(this), String.format("[체결] %s 시장가 매수 완료 (%.8f개)", selectedCoinCode, buyQty));
                    loadUserData();
                } else throw new RuntimeException("DB 저장 실패");
            } else { 
                if (mockBalance.getOrDefault(selectedCoinCode, BigDecimal.ZERO).compareTo(inputVal) < 0) throw new RuntimeException(selectedCoinCode + " 잔고가 부족합니다.");
                BigDecimal sellTotalKRW = inputVal.multiply(currentSelectedPrice);

                OrderDTO marketOrder = new OrderDTO(); marketOrder.setOrderId(System.currentTimeMillis()); marketOrder.setSide("ASK"); marketOrder.setStatus("DONE");
                if (orderDAO.executeMarketOrder(marketOrder, this.userId, currentSelectedPrice, inputVal, sellTotalKRW)) {
                    com.team.coin_simulator.Alerts.NotificationUtil.showToast((JFrame) SwingUtilities.getWindowAncestor(this), String.format("[체결] %s 시장가 매도 완료 (%,.0f KRW)", selectedCoinCode, sellTotalKRW));
                    loadUserData();
                } else throw new RuntimeException("DB 저장 실패");
            }
            marketAmountField.setText(""); 
        } catch (Exception e) { JOptionPane.showMessageDialog(this, "주문 실패: " + e.getMessage(), "에러", JOptionPane.ERROR_MESSAGE); }
    }

    private JPanel createLimitForm() {
        JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setBackground(Color.WHITE);
        p.add(new JLabel("주문가격 (KRW)"));
        priceField = new JTextField(); styleField(priceField); p.add(priceField);
        p.add(Box.createVerticalStrut(10));
        p.add(new JLabel("주문수량"));
        qtyField = new JTextField(); styleField(qtyField); p.add(qtyField);
        return p;
    }

    private JPanel createMarketForm() {
        JPanel p = new JPanel(); p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS)); p.setBackground(Color.WHITE);
        lblMarketUnit = new JLabel("주문총액 (KRW)"); p.add(lblMarketUnit);
        marketAmountField = new JTextField(); styleField(marketAmountField); p.add(marketAmountField);
        p.add(Box.createVerticalStrut(10));
        valExpected = new JLabel("예상 수량: -"); valExpected.setForeground(Color.GRAY); p.add(valExpected);
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
    
    private void loadUserData() {
        mockBalance.clear(); mockLocked.clear(); openOrders.clear(); orderCoinMap.clear();

        orderDAO.getUserAssets(this.userId, mockBalance, mockLocked);
        mockBalance.putIfAbsent("KRW", BigDecimal.ZERO);
        mockLocked.putIfAbsent("KRW", BigDecimal.ZERO);

        List<OrderDTO> fetchedOrders = orderDAO.getOpenOrders(this.userId);
        for (OrderDTO order : fetchedOrders) {
            openOrders.add(order);
            orderCoinMap.put(order.getOrderId(), order.getMarket().replace("KRW-", ""));
        }

        SwingUtilities.invokeLater(() -> {
            updateInfoLabel();
            if (orderEditListPanel != null) {
                orderEditListPanel.updateData(openOrders, orderCoinMap, mockBalance, mockLocked);
            }
        });
    }
}