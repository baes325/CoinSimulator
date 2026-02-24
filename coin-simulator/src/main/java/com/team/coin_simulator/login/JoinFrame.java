package com.team.coin_simulator.login;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.math.BigDecimal;
import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;

import DAO.AssetDAO;
import DAO.UserDAO;
import DTO.UserDTO;

public class JoinFrame extends JFrame {
    // 공통 폰트 설정
    private final Font fontBold = new Font("맑은 고딕", Font.BOLD, 14);
    private final Font fontPlain = new Font("맑은 고딕", Font.PLAIN, 12);
    private final Font fontSmall = new Font("맑은 고딕", Font.PLAIN, 11);

    private UserDAO userDAO = new UserDAO();

    // 입력 필드 배열 (이메일, 비밀번호, 비밀번호 확인, 휴대폰 번호, 초기 자산)
    private JTextField[] fields = new JTextField[5];

    public JoinFrame() {
        setTitle("ONBIT 회원가입");
        setSize(460, 800);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setResizable(false);

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(new Color(245, 247, 250));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setPreferredSize(new Dimension(400, 720));
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(220, 220, 220), 1),
                BorderFactory.createEmptyBorder(40, 40, 40, 40)));

        JLabel titleLabel = new JLabel("회원가입");
        titleLabel.setFont(new Font("맑은 고딕", Font.BOLD, 26));
        titleLabel.setForeground(new Color(33, 33, 33));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(titleLabel);

        JLabel subTitle = new JLabel("ONBIT 가상자산 시뮬레이터에 오신 것을 환영합니다");
        subTitle.setFont(fontSmall);
        subTitle.setForeground(Color.GRAY);
        subTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(subTitle);

        card.add(Box.createVerticalStrut(35));

        String[] labels = {"아이디 (이메일 주소)", "비밀번호", "비밀번호 확인", "휴대폰 번호 (- 제외)", "초기 투자 금액 (KRW)"};

        for (int i = 0; i < labels.length; i++) {
            JTextField field;
            if (i == 1 || i == 2) {
                field = new JPasswordField();
            } else {
                field = new JTextField();
            }

            if (i == 4) {
                field.setText("100000000");
            }

            styleField(field, labels[i]);
            fields[i] = field;
            card.add(field);
            card.add(Box.createVerticalStrut(15));
        }

        JLabel pwHint = new JLabel("<html>* 비밀번호 규칙: 영문, 숫자, 특수문자(!@#$%^&*) 포함 8~16자</html>");
        pwHint.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
        pwHint.setForeground(new Color(33, 99, 184));
        pwHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        card.add(pwHint);

        card.add(Box.createVerticalStrut(30));

        JButton joinBtn = new JButton("가입 완료");
        stylePrimaryBtn(joinBtn);

        joinBtn.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String email = fields[0].getText().trim();
                String pw = new String(((JPasswordField) fields[1]).getPassword());
                String pwConfirm = new String(((JPasswordField) fields[2]).getPassword());
                String phone = fields[3].getText().trim();
                String initialAssetStr = fields[4].getText().trim();

                // 1) 빈칸 체크
                if (email.isEmpty() || pw.isEmpty() || pwConfirm.isEmpty() || phone.isEmpty() || initialAssetStr.isEmpty()) {
                    JOptionPane.showMessageDialog(JoinFrame.this, "모든 정보를 빠짐없이 입력해주세요.", "입력 오류", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                // 2) 초기 투자금액 검사
                BigDecimal initialAsset;
                try {
                    initialAsset = new BigDecimal(initialAssetStr.replaceAll(",", ""));

                    if (initialAsset.compareTo(new BigDecimal("10000")) < 0) {
                        JOptionPane.showMessageDialog(JoinFrame.this,
                                "초기 투자금액은 최소 10,000원 이상이어야 합니다.",
                                "입력 오류", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                    if (initialAsset.compareTo(new BigDecimal("10000000000")) > 0) {
                        JOptionPane.showMessageDialog(JoinFrame.this,
                                "초기 투자금액은 최대 10,000,000,000원 이하여야 합니다.",
                                "입력 오류", JOptionPane.WARNING_MESSAGE);
                        return;
                    }

                } catch (NumberFormatException ex) {
                    JOptionPane.showMessageDialog(JoinFrame.this,
                            "초기 투자금액은 숫자만 입력해주세요.",
                            "입력 오류", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                // 3) 비밀번호 규칙
                if (!isValidPassword(pw)) {
                    JOptionPane.showMessageDialog(JoinFrame.this,
                            "비밀번호가 보안 규칙에 맞지 않습니다.\n(영문, 숫자, 특수문자 조합 8~16자)",
                            "보안 약함", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 4) 비밀번호 일치
                if (!pw.equals(pwConfirm)) {
                    JOptionPane.showMessageDialog(JoinFrame.this, "입력하신 두 비밀번호가 서로 다릅니다.", "확인 필요", JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 5) 이메일 중복 확인 및 가입
                if (userDAO.isIdDuplicate(email)) {
                    JOptionPane.showMessageDialog(JoinFrame.this, "이미 사용 중인 이메일 주소입니다.", "중복 오류", JOptionPane.WARNING_MESSAGE);
                    return;
                }

                UserDTO user = new UserDTO();
                user.setUserId(email);
                user.setPassword(pw);
                user.setNickname(email.split("@")[0]);

                boolean userCreated = userDAO.insertUser(user, phone);

                if (userCreated) {
                    boolean assetCreated = AssetDAO.createInitialAsset(email, 1L, initialAsset);

                    if (assetCreated) {
                        JOptionPane.showMessageDialog(JoinFrame.this,
                                "회원가입이 정상적으로 완료되었습니다!\n" +
                                        "초기 투자금액: " + String.format("%,d", initialAsset.longValue()) + "원\n" +
                                        "로그인 화면으로 이동합니다.",
                                "가입 완료", JOptionPane.INFORMATION_MESSAGE);
                        new LoginFrame();
                        dispose();
                    } else {
                        JOptionPane.showMessageDialog(JoinFrame.this,
                                "회원가입은 완료되었으나 초기 자산 설정에 실패했습니다.\n" +
                                        "관리자에게 문의하거나 로그인 후 자산을 확인해주세요.",
                                "경고", JOptionPane.WARNING_MESSAGE);
                        new LoginFrame();
                        dispose();
                    }
                } else {
                    JOptionPane.showMessageDialog(JoinFrame.this,
                            "서버 오류로 가입에 실패했습니다. 잠시 후 다시 시도해주세요.",
                            "DB 오류", JOptionPane.ERROR_MESSAGE);
                }
            }
        });

        card.add(joinBtn);

        // ✅ 핵심: 어디에 포커스가 있든 Enter 누르면 joinBtn 클릭
        getRootPane().setDefaultButton(joinBtn);

        card.add(Box.createVerticalStrut(20));

        JButton backBtn = new JButton("<html>이미 회원이신가요? <font color='#2163B8'>로그인하기</font></html>");
        backBtn.setFont(fontSmall);
        backBtn.setForeground(Color.GRAY);
        backBtn.setBorderPainted(false);
        backBtn.setContentAreaFilled(false);
        backBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        backBtn.setAlignmentX(Component.CENTER_ALIGNMENT);
        backBtn.addActionListener(e -> {
            new LoginFrame();
            dispose();
        });
        card.add(backBtn);

        root.add(card);
        add(root);
        setVisible(true);
    }

    private boolean isValidPassword(String password) {
        String regex = "^(?=.*[A-Za-z])(?=.*\\d)(?=.*[!@#$%^&*])[A-Za-z\\d!@#$%^&*]{8,16}$";
        return password.matches(regex);
    }

    private void styleField(JTextField field, String title) {
        field.setMaximumSize(new Dimension(330, 55));
        field.setPreferredSize(new Dimension(330, 55));
        field.setFont(fontPlain);

        TitledBorder border = BorderFactory.createTitledBorder(
                new LineBorder(new Color(225, 225, 225), 1), title);
        border.setTitleFont(fontSmall);
        border.setTitleColor(Color.DARK_GRAY);

        field.setBorder(BorderFactory.createCompoundBorder(
                border,
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        field.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private void stylePrimaryBtn(JButton btn) {
        btn.setMaximumSize(new Dimension(330, 50));
        btn.setPreferredSize(new Dimension(330, 50));
        btn.setFont(fontBold);
        btn.setBackground(new Color(33, 99, 184));
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setOpaque(true);
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {}

        SwingUtilities.invokeLater(JoinFrame::new);
    }
}