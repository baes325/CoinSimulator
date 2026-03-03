package com.team.coin_simulator.login;

import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.border.TitledBorder;

import com.team.coin_simulator.MainFrame;
import com.team.coin_simulator.backtest.BacktestSessionDAO;
import com.team.coin_simulator.backtest.SessionManager;

import DAO.UserDAO;
import DTO.SessionDTO;
import DTO.UserDTO;

public class LoginFrame extends JFrame {

    private final Font fontBold = new Font("맑은 고딕", Font.BOLD, 14);
    private final Font fontPlain = new Font("맑은 고딕", Font.PLAIN, 12);
    private final Font fontSmall = new Font("맑은 고딕", Font.PLAIN, 11);

 // LoginFrame 클래스 안에 추가
    private String maskId(String id) {
        if (id == null || id.isBlank()) return id;

        // 이메일인 경우: local-part만 마스킹
        if (id.contains("@")) {
            String[] parts = id.split("@", 2);
            String local = parts[0];
            String domain = parts[1];

            int keep;
            int n = local.length();

            // 길이에 따라 앞에 보여줄 글자 수 조절
            if (n <= 2) keep = 1;        // a*, ab*
            else if (n <= 4) keep = 2;   // ab**, abcd -> ab**
            else keep = 3;               // abc****...

            String visible = local.substring(0, keep);
            int starCount = Math.max(2, n - keep); // 최소 ** 보장
            return visible + "*".repeat(starCount) + "@" + domain;
        }

        // 이메일이 아닌 경우(그냥 아이디): 뒤를 마스킹
        int n = id.length();
        int keep;
        if (n <= 2) keep = 1;
        else if (n <= 4) keep = 2;
        else keep = 3;

        String visible = id.substring(0, keep);
        int starCount = Math.max(2, n - keep);
        return visible + "*".repeat(starCount);
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

        loginBtn.addActionListener(e -> {
            String userId = idField.getText().trim();
            String password = new String(pwField.getPassword());

            if (userId.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(this, "아이디와 비밀번호를 모두 입력해주세요.");
                return;
            }

            UserDTO user = UserDAO.loginCheck(userId, password);

            if (user == null) {
                JOptionPane.showMessageDialog(this, "아이디 또는 비밀번호가 일치하지 않습니다.", "로그인 실패", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ✅ 로그인 성공: 세션 초기화 먼저
            try {
                BacktestSessionDAO sessionDAO = new BacktestSessionDAO();
                SessionDTO realtimeSession = sessionDAO.getOrCreateRealtimeSession(user.getUserId());
                SessionManager.getInstance().setCurrentSession(realtimeSession);
            } catch (Exception ex) {
                System.err.println("[LoginFrame] 세션 초기화 중 오류 발생: " + ex.getMessage());
                ex.printStackTrace();
            }

            // ✅ 글씨 확실히 보이는 커스텀 알림창 (0.5초 후 메인 이동)
            JDialog dialog = new JDialog(LoginFrame.this, "알림", false);
            dialog.setUndecorated(true);
            dialog.setAlwaysOnTop(true);

            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Color.WHITE);
            p.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(220, 220, 220), 1),
                    BorderFactory.createEmptyBorder(14, 22, 14, 22)
            ));

            JLabel msg = new JLabel(user.getNickname() + "님, 환영합니다!");
            msg.setFont(new Font("맑은 고딕", Font.BOLD, 13));
            msg.setForeground(new Color(40, 40, 40));
            p.add(msg, BorderLayout.CENTER);

            dialog.setContentPane(p);
            dialog.pack();
            dialog.setLocationRelativeTo(LoginFrame.this);
            dialog.setVisible(true);

            javax.swing.Timer t = new javax.swing.Timer(500, ev -> {
                dialog.dispose();
                new MainFrame(user.getUserId());
                LoginFrame.this.dispose();
            });
            t.setRepeats(false);
            t.start();
        });

        card.add(loginBtn);

        // ✅ 핵심: 어디에 포커스가 있든 Enter 누르면 loginBtn 클릭
        getRootPane().setDefaultButton(loginBtn);

        // 이하 링크/회원가입 이동
        card.add(Box.createVerticalStrut(25));
        JPanel linkPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 0));
        linkPanel.setOpaque(false);

        JLabel findIdLabel = new JLabel("아이디 찾기");
        JLabel findPwLabel = new JLabel("비밀번호 찾기");
        setupLink(findIdLabel);
        setupLink(findPwLabel);

        findIdLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                String phone = JOptionPane.showInputDialog(LoginFrame.this,
                        "가입 시 등록한 휴대폰 번호를 입력해주세요.", "아이디 찾기", JOptionPane.QUESTION_MESSAGE);
                if (phone == null) return;

                phone = phone.trim().replaceAll("[^0-9]", "");
                if (phone.isEmpty()) return;

                String foundId = UserDAO.findIdByPhone(phone);
                if (foundId != null) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                    	"찾으시는 아이디는 [" + maskId(foundId) + "] 입니다.",
                        "아이디 찾기 성공",
                        JOptionPane.INFORMATION_MESSAGE
                    );
                } else {
           
                	}
                }
            }
        );

        findPwLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {

                JTextField emailField = new JTextField();
                JTextField phoneField = new JTextField();

                JPanel panel = new JPanel(new GridLayout(0, 1, 0, 8));
                panel.add(new JLabel("아이디(이메일)를 입력하세요:"));
                panel.add(emailField);
                panel.add(new JLabel("가입 시 등록한 휴대폰 번호(- 제외):"));
                panel.add(phoneField);

                int result = JOptionPane.showConfirmDialog(
                        LoginFrame.this,
                        panel,
                        "비밀번호 찾기(임시 비밀번호 발급)",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                );
                if (result != JOptionPane.OK_OPTION) return;

                String email = emailField.getText().trim();
                String phone = phoneField.getText().trim().replaceAll("[^0-9]", "");

                if (email.isEmpty() || phone.isEmpty()) {
                    JOptionPane.showMessageDialog(LoginFrame.this, "이메일과 휴대폰 번호를 모두 입력해주세요.");
                    return;
                }

                // 1) 이메일+휴대폰 일치 검증
                boolean ok = UserDAO.verifyUserByEmailAndPhone(email, phone);
                if (!ok) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "입력한 정보와 일치하는 계정이 없습니다.",
                            "발급 실패",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 2) 임시 비밀번호 생성
                String tempPw = "ONBIT" + (int) (Math.random() * 89999 + 10000);

                // 3) DB에 임시 비밀번호 저장 (+ must_change_password=1이면 더 좋음)
                boolean issued = UserDAO.issueTemporaryPassword(email, tempPw);
                if (!issued) {
                    JOptionPane.showMessageDialog(LoginFrame.this,
                            "임시 비밀번호 발급(DB 업데이트)에 실패했습니다.",
                            "DB 오류",
                            JOptionPane.ERROR_MESSAGE);
                    return;
                }

                // 4) ✅ 여기! JOptionPane 대신 복사 가능한 다이얼로그 띄우기
                TempPasswordDialog dialog = new TempPasswordDialog(LoginFrame.this, tempPw);
                dialog.setVisible(true);
            }
        });

        linkPanel.add(findIdLabel);
        JLabel separator = new JLabel("|");
        separator.setForeground(Color.LIGHT_GRAY);
        linkPanel.add(separator);
        linkPanel.add(findPwLabel);
        card.add(linkPanel);

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
            new JoinFrame();
            dispose();
        });

        joinHintPanel.add(joinLabel);
        joinHintPanel.add(goToJoinBtn);
        card.add(joinHintPanel);

        root.add(card);
        add(root);
        setVisible(true);
    }

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

    public static void main(String[] args) {
        SwingUtilities.invokeLater(LoginFrame::new);
    }
}