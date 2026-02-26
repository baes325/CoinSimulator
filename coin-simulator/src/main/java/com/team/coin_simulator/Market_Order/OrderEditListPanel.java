package com.team.coin_simulator.Market_Order;

import DAO.OrderDAO;
import DAO.AutoOrderDAO; // 💡 자동매매 DAO 추가
import DTO.OrderDTO;
import DTO.AutoOrderDTO; // 💡 자동매매 DTO 추가
import javax.swing.*;
import java.awt.*;
import java.awt.event.ItemEvent;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;

public class OrderEditListPanel extends JPanel {
    private String userId;
    private OrderDAO orderDAO;
    private AutoOrderDAO autoOrderDAO;
    private Runnable onUpdateCallback; //메인 화면(OrderPanel)을 새로고침

    private JComboBox<String> filterComboBox;
    private JPanel listContainer;
    private boolean isUpdatingComboBox = false;

    private List<OrderDTO> openOrders = new ArrayList<>();
    private List<AutoOrderDTO> activeAutoOrders = new ArrayList<>(); // 자동매매 리스트 추가
    
    private Map<Long, String> orderCoinMap = new HashMap<>();
    private Map<String, BigDecimal> mockBalance = new HashMap<>();
    private Map<String, BigDecimal> mockLocked = new HashMap<>();

    private final Color COLOR_BID = new Color(200, 30, 30);
    private final Color COLOR_ASK = new Color(30, 70, 200);
    private final Color COLOR_AUTO = new Color(155, 89, 182);

    // 생성자
    public OrderEditListPanel(String userId, Runnable onUpdateCallback) {
        this.userId = userId;
        this.onUpdateCallback = onUpdateCallback;
        this.orderDAO = new OrderDAO();
        this.autoOrderDAO = new AutoOrderDAO(); // 초기화

        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        // 1. 상단 필터 패널
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        filterPanel.setBackground(Color.WHITE);
        filterPanel.add(new JLabel("코인 필터: "));

        filterComboBox = new JComboBox<>(new String[]{"전체"});
        filterComboBox.setBackground(Color.WHITE);
        filterComboBox.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) {
                renderList();
            }
        });
        filterPanel.add(filterComboBox);
        add(filterPanel, BorderLayout.NORTH);

        // 2. 리스트 컨테이너
        listContainer = new JPanel();
        listContainer.setLayout(new BoxLayout(listContainer, BoxLayout.Y_AXIS));
        listContainer.setBackground(new Color(245, 245, 245));
        JScrollPane scrollPane = new JScrollPane(listContainer);
        scrollPane.setBorder(null);
        add(scrollPane, BorderLayout.CENTER);
    }

    //메인 화면(OrderPanel)에서 최신 데이터를 던져줄 때 받는 곳
    public void updateData(List<OrderDTO> orders, Map<Long, String> coinMap, Map<String, BigDecimal> balances, Map<String, BigDecimal> lockeds) {
        this.openOrders = orders;
        this.orderCoinMap = coinMap;
        this.mockBalance = balances;
        this.mockLocked = lockeds;

        //일반 주문을 받을 때, 내 예약 주문(AutoOrder)도 같이 DB에서 가져옵니다!
        long currentSessionId = com.team.coin_simulator.backtest.SessionManager.getInstance().getCurrentSessionId();
        this.activeAutoOrders = autoOrderDAO.getActiveAutoOrders(this.userId, currentSessionId);

        if (isUpdatingComboBox) return;

        Set<String> activeCoins = new HashSet<>();
        for (OrderDTO o : openOrders) {
            activeCoins.add(orderCoinMap.getOrDefault(o.getOrderId(), "BTC"));
        }
        //필터용 코인 목록에 예약 주문된 코인들도 추가!
        for (AutoOrderDTO ao : activeAutoOrders) {
            activeCoins.add(ao.getMarket().replace("KRW-", ""));
        }

        String currentSelection = (String) filterComboBox.getSelectedItem();
        isUpdatingComboBox = true;
        filterComboBox.removeAllItems();
        filterComboBox.addItem("전체");
        for (String coin : activeCoins) {
            filterComboBox.addItem(coin);
        }

        if (currentSelection != null && (activeCoins.contains(currentSelection) || currentSelection.equals("전체"))) {
            filterComboBox.setSelectedItem(currentSelection);
        } else {
            filterComboBox.setSelectedIndex(0);
        }
        isUpdatingComboBox = false;

        renderList(); // 데이터 갱신 후 화면 다시 그리기
    }

    // 리스트 그리기
    private void renderList() {
        listContainer.removeAll();
        String currentSelection = (String) filterComboBox.getSelectedItem();
        if (currentSelection == null) currentSelection = "전체";

        // 1. 일반 미체결 주문 먼저 그리기
        for (OrderDTO order : openOrders) {
            String orderCoin = orderCoinMap.getOrDefault(order.getOrderId(), "BTC");
            if (currentSelection.equals("전체") || currentSelection.equals(orderCoin)) {
                listContainer.add(createOrderItem(order, orderCoin));
                listContainer.add(Box.createVerticalStrut(10));
            }
        }
        
        // 2. 자동/예약 주문 이어서 그리기
        for (AutoOrderDTO autoOrder : activeAutoOrders) {
            String autoCoin = autoOrder.getMarket().replace("KRW-", "");
            if (currentSelection.equals("전체") || currentSelection.equals(autoCoin)) {
                listContainer.add(createAutoOrderItem(autoOrder, autoCoin));
                listContainer.add(Box.createVerticalStrut(10));
            }
        }
        
        listContainer.revalidate();
        listContainer.repaint();
    }

    //자동/예약 주문 전용 카드 생성
    private JPanel createAutoOrderItem(AutoOrderDTO order, String coinCode) {
        JPanel item = new JPanel(new BorderLayout(10, 5));
        item.setBackground(Color.WHITE);
        // 일반 주문과 구분하기 위해 보라색 굵은 테두리 적용!
        item.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(COLOR_AUTO, 2),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)));
        item.setMaximumSize(new Dimension(360, 140)); 
        
        String sideTxt = order.getSide().equals("BID") ? "매수" : "매도";
        JLabel typeLbl = new JLabel("[예약] " + sideTxt + " (" + coinCode + ")"); 
        typeLbl.setForeground(COLOR_AUTO);
        typeLbl.setFont(new Font("맑은 고딕", Font.BOLD, 14));

        JPanel center = new JPanel(new GridLayout(2, 2));
        center.setBackground(Color.WHITE);
        center.add(new JLabel("격발 목표가"));
        center.add(new JLabel(String.format("%,.0f KRW", order.getTriggerPrice()), SwingConstants.RIGHT));
        
        String condTxt = order.getConditionType().equals("ABOVE") ? "이상(돌파)" : "이하(이탈)";
        center.add(new JLabel("조건 / 수량")); 
        center.add(new JLabel(condTxt + " / " + order.getVolume() + "개", SwingConstants.RIGHT)); 
        
        JPanel btnPanel = new JPanel(new GridLayout(1, 1, 5, 0)); 
        btnPanel.setBackground(Color.WHITE);

        JButton btnCancel = new JButton("예약 취소");
        btnCancel.setBackground(new Color(255, 230, 230));
        btnCancel.setForeground(Color.RED);
        btnCancel.setFocusPainted(false);
        btnCancel.addActionListener(e -> cancelAutoOrder(order)); 

        btnPanel.add(btnCancel);

        item.add(typeLbl, BorderLayout.NORTH);
        item.add(center, BorderLayout.CENTER);
        item.add(btnPanel, BorderLayout.SOUTH);
        return item;
    }

    // 예약 주문 취소 로직
    private void cancelAutoOrder(AutoOrderDTO order) {
        int confirm = JOptionPane.showConfirmDialog(this, "특수 예약 주문을 취소하시겠습니까?", "취소 확인", JOptionPane.YES_NO_OPTION);
        if (confirm == JOptionPane.YES_OPTION) {
            boolean isDBSuccess = autoOrderDAO.cancelAutoOrder(order.getAutoId());
            if (isDBSuccess) {
                JOptionPane.showMessageDialog(this, "예약 주문이 정상적으로 취소되었습니다.");
                
                // 취소 후 메인 프레임의 엔진에게 "예약 빠졌으니 다시 로드해!" 라고 알려줍니다.
                com.team.coin_simulator.MainFrame mainFrame = (com.team.coin_simulator.MainFrame) SwingUtilities.getWindowAncestor(this);
                if (mainFrame != null && mainFrame.getAutoOrderService() != null) {
                    mainFrame.getAutoOrderService().reloadAutoOrdersFromDB();
                }
                
                // UI 새로고침
                if (onUpdateCallback != null) onUpdateCallback.run(); 
            } else {
                JOptionPane.showMessageDialog(this, "취소 처리에 실패했습니다. (이미 체결되었을 수 있습니다.)", "오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    //일반 주문 카드 생성
    private JPanel createOrderItem(OrderDTO order, String coinCode) {
        JPanel item = new JPanel(new BorderLayout(10, 5));
        item.setBackground(Color.WHITE);
        item.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(230, 230, 230)),
                BorderFactory.createEmptyBorder(10, 15, 10, 15)));
        item.setMaximumSize(new Dimension(360, 140)); 
        
        String sideTxt = order.getSide().equals("BID") ? "매수" : "매도";
        JLabel typeLbl = new JLabel(sideTxt + " (" + coinCode + ")"); 
        typeLbl.setForeground(order.getSide().equals("BID") ? COLOR_BID : COLOR_ASK);
        typeLbl.setFont(new Font("맑은 고딕", Font.BOLD, 14));

        JPanel center = new JPanel(new GridLayout(2, 2));
        center.setBackground(Color.WHITE);
        center.add(new JLabel("가격"));
        center.add(new JLabel(String.format("%,.0f KRW", order.getOriginalPrice()), SwingConstants.RIGHT));
        
        center.add(new JLabel("미체결 수량")); 
        center.add(new JLabel(order.getRemainingVolume() + " " + coinCode, SwingConstants.RIGHT)); 
        
        JPanel btnPanel = new JPanel(new GridLayout(1, 2, 5, 0)); 
        btnPanel.setBackground(Color.WHITE);

        JButton btnModify = new JButton("정정");
        btnModify.setBackground(new Color(240, 240, 240));
        btnModify.addActionListener(e -> showModifyDialog(order, coinCode)); 

        JButton btnCancel = new JButton("취소");
        btnCancel.setBackground(new Color(255, 230, 230));
        btnCancel.setForeground(Color.RED);
        btnCancel.addActionListener(e -> cancelOrder(order, coinCode)); 

        btnPanel.add(btnModify);
        btnPanel.add(btnCancel);

        item.add(typeLbl, BorderLayout.NORTH);
        item.add(center, BorderLayout.CENTER);
        item.add(btnPanel, BorderLayout.SOUTH);
        return item;
    }

    //일반 취소 로직
    private void cancelOrder(OrderDTO order, String coinCode) {
        BigDecimal currentRemaining = order.getRemainingVolume();
        BigDecimal lockedAmt;
        if (order.getSide().equals("BID")) {
            BigDecimal remainingCost = order.getOriginalPrice().multiply(currentRemaining);
            BigDecimal fee = remainingCost.multiply(new BigDecimal("0.0005"));
            lockedAmt = remainingCost.add(fee);
        } else {
            lockedAmt = currentRemaining;
        }

        boolean isDBSuccess = orderDAO.cancelOrder(order.getOrderId(), this.userId, order.getSide(), lockedAmt);
        
        if (isDBSuccess) {
            JOptionPane.showMessageDialog(this, "주문이 취소되었습니다.");
            if (onUpdateCallback != null) onUpdateCallback.run(); 
        } else {
            JOptionPane.showMessageDialog(this, "취소 처리에 실패했습니다. (이미 체결되었을 수 있습니다.)", "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    //일반 정정 로직
    private void showModifyDialog(OrderDTO order, String coinCode) {
        JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
        JTextField txtPrice = new JTextField(order.getOriginalPrice().toString());
        JTextField txtQty = new JTextField(order.getRemainingVolume().toString());

        panel.add(new JLabel("정정 가격(KRW):")); panel.add(txtPrice);
        panel.add(new JLabel("정정 수량(" + coinCode + "):")); panel.add(txtQty);

        if (JOptionPane.showConfirmDialog(this, panel, "주문 정정", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
            try {
                BigDecimal newPrice = new BigDecimal(txtPrice.getText().replace(",", ""));
                BigDecimal newQty = new BigDecimal(txtQty.getText().replace(",", ""));

                BigDecimal oldLockedAmt = order.getSide().equals("BID") ? 
                                          order.getOriginalPrice().multiply(order.getRemainingVolume()) : order.getRemainingVolume();

                BigDecimal currentBalance = mockBalance.getOrDefault(order.getSide().equals("BID") ? "KRW" : coinCode, BigDecimal.ZERO);
                BigDecimal tempBalance = currentBalance.add(oldLockedAmt);
                BigDecimal newRequiredAmt = order.getSide().equals("BID") ? newPrice.multiply(newQty) : newQty;

                if (tempBalance.compareTo(newRequiredAmt) < 0) {
                    throw new RuntimeException("잔고가 부족합니다.");
                }

                if (orderDAO.modifyOrder(order.getOrderId(), this.userId, order.getSide(), oldLockedAmt, newRequiredAmt, newPrice, newQty)) {
                    JOptionPane.showMessageDialog(this, "주문이 정정되었습니다.");
                    if (onUpdateCallback != null) onUpdateCallback.run(); 
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "정정 실패: " + ex.getMessage(), "오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}