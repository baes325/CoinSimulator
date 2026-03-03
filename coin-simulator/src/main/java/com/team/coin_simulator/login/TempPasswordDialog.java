package com.team.coin_simulator.login;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;

public class TempPasswordDialog extends JDialog {

    private static final Color BLUE = new Color(33, 99, 199);

    public TempPasswordDialog(Window owner, String tempPassword) {
        super(owner, "임시 비밀번호 발급", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel root = new JPanel();
        root.setBackground(Color.WHITE);
        root.setBorder(new EmptyBorder(18, 18, 18, 18));
        root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));

        JLabel title = new JLabel("임시 비밀번호가 발급되었습니다");
        title.setFont(new Font("맑은 고딕", Font.BOLD, 18));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel guide = new JLabel("<html>아래 임시 비밀번호로 로그인한 뒤<br>메인 화면에서 비밀번호를 변경해주세요.</html>");
        guide.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
        guide.setForeground(new Color(90, 90, 90));
        guide.setBorder(new EmptyBorder(8, 0, 12, 0));
        guide.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel pwRow = new JPanel(new BorderLayout(10, 0));
        pwRow.setBackground(Color.WHITE);
        pwRow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextField pwField = new JTextField(tempPassword);
        pwField.setEditable(false);
        pwField.setFont(new Font("Consolas", Font.BOLD, 16));
        pwField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(220, 220, 220)),
                new EmptyBorder(10, 12, 10, 12)
        ));
        pwField.setBackground(Color.WHITE);

        JButton copyBtn = new JButton("복사");
        stylePrimaryButton(copyBtn);
        copyBtn.setPreferredSize(new Dimension(90, 40));
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(tempPassword), null);
            JOptionPane.showMessageDialog(this, "임시 비밀번호가 클립보드에 복사되었습니다.");
        });

        pwRow.add(pwField, BorderLayout.CENTER);
        pwRow.add(copyBtn, BorderLayout.EAST);

        JButton okBtn = new JButton("확인");
        stylePrimaryButton(okBtn);
        okBtn.setAlignmentX(Component.LEFT_ALIGNMENT);
        okBtn.setBorder(new EmptyBorder(12, 0, 0, 0));
        okBtn.addActionListener(e -> dispose());

        root.add(title);
        root.add(guide);
        root.add(pwRow);
        root.add(okBtn);

        setContentPane(root);
        setSize(420, 230);
        setLocationRelativeTo(owner);

        SwingUtilities.invokeLater(() -> {
            pwField.requestFocusInWindow();
            pwField.selectAll(); // 드래그/복사도 가능
        });
    }

    private void stylePrimaryButton(JButton btn) {
        btn.setBackground(BLUE);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
}