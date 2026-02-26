package com.team.coin_simulator.Alerts;

import DAO.PriceAlertDAO;
import DTO.PriceAlertDTO;

import javax.swing.*;
import java.awt.*;
import java.math.BigDecimal;
import java.util.List;

public class AlertDialog extends JDialog {
    private PriceAlertDAO alertDAO = new PriceAlertDAO();
    private String market; 
    private String userId;
    private PriceAlertService alertService;
    
    // 알림 목록이 그려질 도화지(패널)
    private JPanel listPanel; 

    public AlertDialog(JFrame parent, String market, BigDecimal currentPrice, String userId, PriceAlertService alertService) {
        super(parent, "가격 알림 설정 및 관리", true);
        this.market = market;
        this.userId = userId;
        this.alertService = alertService;
        
        setSize(400, 500); 
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout(10, 10));

        // 1. 위쪽: 알림 추가 폼 세팅
        JPanel topForm = createAddForm(currentPrice);
        add(topForm, BorderLayout.NORTH);

        // 2. 아래쪽: 내 알림 목록 세팅 (스크롤 기능 포함)
        listPanel = new JPanel();
        listPanel.setLayout(new BoxLayout(listPanel, BoxLayout.Y_AXIS));
        listPanel.setBackground(Color.WHITE);
        
        JScrollPane scrollPane = new JScrollPane(listPanel);
        scrollPane.setBorder(BorderFactory.createTitledBorder("내 알림 목록"));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16); // 스크롤 속도 조절
        add(scrollPane, BorderLayout.CENTER);

        // 창이 켜질 때 DB에서 내 알림 목록을 불러와서 그려줍니다.
        refreshAlertList();
    }

    // 알림 추가 폼
    private JPanel createAddForm(BigDecimal currentPrice) {
        JPanel panel = new JPanel(new GridLayout(4, 2, 5, 5));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        panel.add(new JLabel("선택된 코인:"));
        panel.add(new JLabel(market));

        panel.add(new JLabel("목표 가격(KRW):"));
        JTextField tfPrice = new JTextField(currentPrice.toString()); 
        panel.add(tfPrice);

        panel.add(new JLabel("조건:"));
        String[] conditions = {"이상 (상승돌파)", "이하 (하락돌파)"};
        JComboBox<String> cbCondition = new JComboBox<>(conditions);
        panel.add(cbCondition);

        JButton btnCancel = new JButton("닫기");
        JButton btnAdd = new JButton("알림 추가");
        
        btnCancel.addActionListener(e -> dispose()); 
        
        btnAdd.addActionListener(e -> {
            try {
                BigDecimal target = new BigDecimal(tfPrice.getText().replace(",", ""));
                String cond = (cbCondition.getSelectedIndex() == 0) ? "ABOVE" : "BELOW";

                if (target.compareTo(currentPrice) == 0) {
                    JOptionPane.showMessageDialog(this, "현재가와 동일한 가격은 설정할 수 없습니다.", "경고", JOptionPane.WARNING_MESSAGE);
                    return; 
                }
                if (cond.equals("ABOVE") && target.compareTo(currentPrice) < 0) {
                    JOptionPane.showMessageDialog(this, "상승 돌파는 현재가보다 높게 설정해야 합니다.", "설정 오류", JOptionPane.ERROR_MESSAGE);
                    return; 
                }
                if (cond.equals("BELOW") && target.compareTo(currentPrice) > 0) {
                    JOptionPane.showMessageDialog(this, "하락 돌파는 현재가보다 낮게 설정해야 합니다.", "설정 오류", JOptionPane.ERROR_MESSAGE);
                    return; 
                }
                
                boolean success = alertDAO.addPriceAlert(userId, market, target, cond);
                
                if (success) {
                    if (this.alertService != null) this.alertService.reloadAlertsFromDB(); // 메모리 갱신
                    refreshAlertList(); // 추가 성공 시 목록 새로고침
                    tfPrice.setText(currentPrice.toString()); // 입력칸 초기화
                    JOptionPane.showMessageDialog(this, "알림이 추가되었습니다!");
                } else {
                    JOptionPane.showMessageDialog(this, "추가 실패 (DB 오류)");
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "가격을 올바른 숫자로 입력해주세요.");
            }
        });

        panel.add(btnCancel);
        panel.add(btnAdd);
        return panel;
    }

    // DB에서 내 알림들을 가져와서 화면에 그려주는 메서드
    private void refreshAlertList() {
        listPanel.removeAll(); // 기존에 그려진 목록 싹 지우기
        
        List<PriceAlertDTO> alerts = alertDAO.getAllAlertsForUser(userId);

        if(alerts.isEmpty()) {
            JLabel emptyLabel = new JLabel("등록된 알림이 없습니다.");
            emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            listPanel.add(Box.createVerticalStrut(20)); // 위쪽 여백
            listPanel.add(emptyLabel);
        } else {
            for(PriceAlertDTO alert : alerts) {
                listPanel.add(createAlertRow(alert)); // 알림 1개당 한 줄씩 추가
            }
        }
        
        listPanel.revalidate();
        listPanel.repaint();
    }

  //알림 1줄(Row)의 디자인과 버튼
    private JPanel createAlertRow(PriceAlertDTO alert) {
        JPanel row = new JPanel(new BorderLayout(5, 5));
        row.setBackground(Color.WHITE);
        row.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY)); // 밑줄 긋기
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40)); // 높이 고정

        // 텍스트 구성 (예: [BTC] 100,000,000 돌파)
        String cond = alert.getConditionType().equals("ABOVE") ? "이상" : "이하";
        String cleanMarket = alert.getMarket().replace("KRW-", "");
        
        String infoText = String.format("[%s] %,.0f %s", cleanMarket, alert.getTargetPrice(), cond);
        JLabel lblInfo = new JLabel(infoText);
        lblInfo.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        
        if (!alert.isActive()) lblInfo.setForeground(Color.GRAY); // 꺼진 알림은 회색 처리
        row.add(lblInfo, BorderLayout.CENTER);

        // 우측 버튼들 (재활성화, 삭제)
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
        btnPanel.setBackground(Color.WHITE);

        // 꺼진 알림(is_active = FALSE)일 때만 '재활성화' 버튼 보이기
        if (!alert.isActive()) {
            JButton btnReactivate = new JButton("재활성화");
            btnReactivate.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
            btnReactivate.setBackground(new Color(46, 204, 113)); // 에메랄드 그린
            btnReactivate.setForeground(Color.WHITE);
            btnReactivate.setOpaque(true);
            btnReactivate.setBorderPainted(false);
            btnReactivate.setFocusPainted(false);
            
            // 마우스 애니메이션 (재활성화 버튼)
            btnReactivate.addMouseListener(new java.awt.event.MouseAdapter() {
                public void mouseEntered(java.awt.event.MouseEvent evt) { btnReactivate.setBackground(new Color(39, 174, 96)); }
                public void mouseExited(java.awt.event.MouseEvent evt)  { btnReactivate.setBackground(new Color(46, 204, 113)); }
                public void mousePressed(java.awt.event.MouseEvent evt) { btnReactivate.setBackground(new Color(30, 130, 76)); }
                public void mouseReleased(java.awt.event.MouseEvent evt) { btnReactivate.setBackground(new Color(39, 174, 96)); }
            });

            btnReactivate.addActionListener(e -> {
                if (alertDAO.reactivateAlert(alert.getAlertId())) {
                    if(alertService != null) alertService.reloadAlertsFromDB();
                    refreshAlertList(); // 화면 새로고침
                }
            });
            btnPanel.add(btnReactivate);
        }

        //삭제 버튼
        JButton btnDelete = new JButton("삭제");
        btnDelete.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        btnDelete.setBackground(new Color(231, 76, 60)); // 빨간색
        btnDelete.setForeground(Color.WHITE);
        btnDelete.setOpaque(true);           
        btnDelete.setBorderPainted(false);   
        btnDelete.setFocusPainted(false);    
        
        // 마우스 애니메이션 (삭제 버튼)
        btnDelete.addMouseListener(new java.awt.event.MouseAdapter() {
            public void mouseEntered(java.awt.event.MouseEvent evt) { btnDelete.setBackground(new Color(192, 57, 43)); }
            public void mouseExited(java.awt.event.MouseEvent evt)  { btnDelete.setBackground(new Color(231, 76, 60)); }
            public void mousePressed(java.awt.event.MouseEvent evt) { btnDelete.setBackground(new Color(150, 40, 30)); }
            public void mouseReleased(java.awt.event.MouseEvent evt) { btnDelete.setBackground(new Color(192, 57, 43)); }
        });
        
        btnDelete.addActionListener(e -> {
            int confirm = JOptionPane.showConfirmDialog(this, "정말 삭제하시겠습니까?", "삭제 확인", JOptionPane.YES_NO_OPTION);
            if (confirm == JOptionPane.YES_OPTION) {
                if (alertDAO.deleteAlert(alert.getAlertId())) {
                    if(alertService != null) alertService.reloadAlertsFromDB();
                    refreshAlertList(); // 화면 새로고침
                }
            }
        });
        btnPanel.add(btnDelete);

        row.add(btnPanel, BorderLayout.EAST);
        return row;
    }
}