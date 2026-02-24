package com.team.coin_simulator.login;

import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;

import com.team.coin_simulator.MainFrame;
// 세션 관리를 위한 import 추가
import com.team.coin_simulator.backtest.BacktestSessionDAO;
import com.team.coin_simulator.backtest.SessionManager;

import DAO.UserDAO;
import DTO.SessionDTO;
import DTO.UserDTO;

import java.awt.datatransfer.StringSelection;
import java.awt.Toolkit;


public class LoginFrame extends JFrame {

    private final Font fontBold = new Font("맑은 고딕", Font.BOLD, 14);
    private final Font fontPlain = new Font("맑은 고딕", Font.PLAIN, 12);
    private final Font fontSmall = new Font("맑은 고딕", Font.PLAIN, 11);

    private void showTempPasswordDialog(String tempPw) {
        JTextField pwField = new JTextField(tempPw);
        pwField.setEditable(false);
        pwField.setFont(new Font("맑은 고딕", Font.BOLD, 14));

        JButton copyBtn = new JButton("복사");
        copyBtn.addActionListener(ev -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(tempPw), null);
            JOptionPane.showMessageDialog(this, "클립보드에 복사되었습니다!");
        });

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.add(new JLabel("임시 비밀번호가 재설정되었습니다."));
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("임시 비밀번호 (복사 가능):"));
        panel.add(Box.createVerticalStrut(6));
        panel.add(pwField);
        panel.add(Box.createVerticalStrut(10));
        panel.add(copyBtn);
        panel.add(Box.createVerticalStrut(8));
        panel.add(new JLabel("※ 로그인 후 [비밀번호 변경]에서 새 비밀번호로 바꾸세요."));

        JOptionPane.showMessageDialog(this, panel, "비밀번호 재설정 완료", JOptionPane.INFORMATION_MESSAGE);
    }

    public LoginFrame() {
        setTitle("ONBIT 로그인");
        setSize(460, 700);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setResizable(false);

        JPanel root = new JPanel(new GridBagLayout());
        root.setBackground(new Color(245, 247, 250));

        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBackground(Color.WHITE);
        card.setPreferredSize(new Dimension(380, 580));
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(230, 230, 230), 1),
                new EmptyBorder(45, 35, 40, 35)));

        JLabel logo = new JLabel("ONBIT");
        logo.setFont(new Font("Arial", Font.BOLD, 32));
        logo.setForeground(new Color(33, 99, 184));
        logo.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel subtitle = new JLabel("로그인");
        subtitle.setFont(fontPlain);
        subtitle.setForeground(Color.GRAY);
        subtitle.setAlignmentX(Component.CENTER_ALIGNMENT);

        card.add(logo);
        card.add(Box.createVerticalStrut(5));
        card.add(subtitle);
        card.add(Box.createVerticalStrut(40));

        JTextField idField = new JTextField();
        JPasswordField pwField = new JPasswordField();
        styleField(idField, "아이디 (이메일)");
        styleField(pwField, "비밀번호");

        card.add(idField);
        card.add(Box.createVerticalStrut(15));
        card.add(pwField);
        card.add(Box.createVerticalStrut(25));

        JButton loginBtn = new JButton("로그인");
        stylePrimaryBtn(loginBtn);

        // 로그인 액션 (버튼 + Enter 공통)
        ActionListener loginAction = e -> {
            String userId = idField.getText().trim();
            String password = new String(pwField.getPassword());

            if (userId.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "아이디와 비밀번호를 모두 입력해주세요.");
                return;
            }

            UserDTO user = UserDAO.loginCheck(userId, password);
            if (user != null) {
                JOptionPane.showMessageDialog(this, user.getNickname() + "님, 환영합니다!");
                SwingUtilities.invokeLater(() -> new MainFrame(user.getUserId().trim()));
                dispose();
            } else {
                JOptionPane.showMessageDialog(this,
                        "아이디 또는 비밀번호가 일치하지 않습니다.",
                        "로그인 실패",
                        JOptionPane.ERROR_MESSAGE);
            }
        };

        loginBtn.addActionListener(loginAction);
        idField.addActionListener(loginAction);
        pwField.addActionListener(loginAction);

        card.add(loginBtn);

        // 아이디/비밀번호 찾기 링크
        card.add(Box.createVerticalStrut(25));
        JPanel linkPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        linkPanel.setOpaque(false);

        JLabel findIdLabel = new JLabel("아이디 찾기");
        JLabel findPwLabel = new JLabel("비밀번호 찾기");
        setupLink(findIdLabel);
        setupLink(findPwLabel);

        // ===============================
        // 아이디 찾기: phone -> user_id 조회 후 "스타일B(앞5글자만)" 마스킹 출력
        // ===============================
        findIdLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String phone = JOptionPane.showInputDialog(LoginFrame.this,
                        "가입 시 등록한 휴대폰 번호를 입력해주세요.\n(숫자만 입력 권장)",
                        "아이디 찾기",
                        JOptionPane.QUESTION_MESSAGE);
                if (phone == null) return;

                phone = phone.trim().replaceAll("[^0-9]", "");
                if (phone.isEmpty()) return;

                String foundId = UserDAO.findIdByPhone(phone);
                if (foundId == null) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "해당 번호로 가입된 정보가 없습니다.",
                            "찾기 실패",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                String masked = maskIdStyleB(foundId);
                JOptionPane.showMessageDialog(LoginFrame.this,
                        "찾으시는 아이디는 [" + masked + "] 입니다.",
                        "아이디 찾기 성공",
                        JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // ===============================
        // 비밀번호 찾기: 메일 X
        // 아이디+휴대폰번호 일치하면 임시 비번으로 즉시 재설정 후, 화면에는 마스킹해서 보여주기
        // ===============================
        findPwLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {

                String inputId = JOptionPane.showInputDialog(LoginFrame.this,
                        "아이디(이메일)를 입력해주세요.",
                        "비밀번호 찾기",
                        JOptionPane.QUESTION_MESSAGE);
                if (inputId == null) return;

                String userId = inputId.trim();
                if (userId.isEmpty()) return;

                String inputPhone = JOptionPane.showInputDialog(LoginFrame.this,
                        "가입 시 등록한 휴대폰 번호를 입력해주세요.\n(숫자만 입력 권장)",
                        "비밀번호 찾기",
                        JOptionPane.QUESTION_MESSAGE);
                if (inputPhone == null) return;

                String phone = inputPhone.trim().replaceAll("[^0-9]", "");
                if (phone.isEmpty()) return;

                // 1) 아이디+전화번호 일치 확인
                if (!UserDAO.verifyUserByIdAndPhone(userId, phone)) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "등록되지 않았거나 정보가 일치하지 않습니다.",
                            "비밀번호 찾기 실패",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 2) 임시 비밀번호 생성
                String tempPw = UserDAO.generateTempPassword(10);

                // 3) DB 비밀번호를 임시 비밀번호로 재설정
                boolean ok = UserDAO.updatePassword(userId, tempPw);
                if (!ok) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "임시 비밀번호 발급(재설정)에 실패했습니다.",
                            "오류",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 4) 팝업: 임시 비밀번호 표시 + 복사 버튼
                showTempPasswordDialog(tempPw);
            }
        });
        linkPanel.add(findIdLabel);
        JLabel separator = new JLabel("|");
        separator.setForeground(Color.LIGHT_GRAY);
        linkPanel.add(separator);
        linkPanel.add(findPwLabel);
        card.add(linkPanel);

        // 회원가입 이동
        card.add(Box.createVerticalStrut(35));
        JPanel joinHintPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        joinHintPanel.setOpaque(false);

        JLabel joinLabel = new JLabel("아직 회원이 아니신가요?");
        joinLabel.setFont(fontSmall);
        joinLabel.setForeground(Color.DARK_GRAY);

        JButton goToJoinBtn = new JButton("회원가입");
        goToJoinBtn.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        goToJoinBtn.setForeground(new Color(33, 99, 184));
        goToJoinBtn.setContentAreaFilled(false);
        goToJoinBtn.setBorderPainted(false);
        goToJoinBtn.setFocusPainted(false);
        goToJoinBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        goToJoinBtn.setMargin(new Insets(0, 0, 0, 0));
        goToJoinBtn.addActionListener(e -> {
            try {
                JoinFrame join = new JoinFrame();   // 여기서 예외 나면 아래로 떨어짐
                join.setVisible(true);              // JoinFrame 생성자에서 setVisible 안 하면 이 줄이 필요
                dispose();                          // ✅ JoinFrame이 뜬 다음에 닫기
            } catch (Throwable ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(
                    LoginFrame.this,
                    "JoinFrame 열기 실패: " + ex.getClass().getSimpleName() + "\n" + ex.getMessage(),
                    "오류",
                    JOptionPane.ERROR_MESSAGE
                );
            }
        });

        joinHintPanel.add(joinLabel);
        joinHintPanel.add(goToJoinBtn);
        card.add(joinHintPanel);

        root.add(card);
        add(root);
        setVisible(true);
    }

    // ---------------- UI helpers ----------------

    private void setupLink(JLabel label) {
        label.setFont(fontSmall);
        label.setForeground(Color.GRAY);
        label.setCursor(new Cursor(Cursor.HAND_CURSOR));
    }

    private void styleField(JTextField field, String title) {
        field.setMaximumSize(new Dimension(320, 50));
        field.setPreferredSize(new Dimension(320, 50));
        TitledBorder border = BorderFactory.createTitledBorder(
                new LineBorder(new Color(230, 230, 230)), title);
        border.setTitleFont(fontSmall);
        field.setBorder(border);
        field.setFont(fontPlain);
        field.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    private void stylePrimaryBtn(JButton btn) {
        btn.setMaximumSize(new Dimension(320, 50));
        btn.setPreferredSize(new Dimension(320, 50));
        btn.setBackground(new Color(33, 99, 184));
        btn.setForeground(Color.WHITE);
        btn.setFont(fontBold);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);	
        btn.setOpaque(true);
        btn.setAlignmentX(Component.CENTER_ALIGNMENT);
    }

    // ---------------- Masking ----------------

    // 아이디 마스킹 스타일 B: 앞 5글자만 보이고 나머지 *
    // 이메일이면 @ 앞(local-part)만 마스킹
    private String maskIdStyleB(String userId) {
        if (userId == null || userId.isBlank()) return "";

        int at = userId.indexOf('@');
        if (at > 0) {
            String local = userId.substring(0, at);
            String domain = userId.substring(at);
            return maskLocalStyleB(local) + domain;
        }
        return maskLocalStyleB(userId);
    }

    private String maskLocalStyleB(String s) {
        int n = s.length();
        if (n <= 1) return "*";
        if (n <= 5) return s.charAt(0) + "*".repeat(n - 1);
        return s.substring(0, 5) + "*".repeat(n - 5);
    }

    // 비밀번호 마스킹: 앞2 + 뒤2만 보여주고 나머지 *
    private String maskPassword(String pw) {
        if (pw == null || pw.isBlank()) return "";
        int n = pw.length();
        if (n <= 2) return "*".repeat(n);
        if (n <= 4) return pw.charAt(0) + "*".repeat(n - 1);

        int prefix = 2, suffix = 2;
        int stars = Math.max(1, n - (prefix + suffix));
        return pw.substring(0, prefix) + "*".repeat(stars) + pw.substring(n - suffix);
    }

    public static void main(String[] args) {
    	SwingUtilities.invokeLater(LoginFrame::new);
    }
}
