package com.team.coin_simulator.backtest;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;

import DAO.AssetDAO;
import DTO.SessionDTO;

/**
 * 백테스팅 세션 생성 및 선택 다이얼로그
 *
 * ■ 탭 구성
 *   - [기존 세션] : 목록 테이블 → 선택 후 입장
 *   - [새 세션 만들기] : 날짜 범위 안에서 랜덤 or 직접 지정
 *
 * ■ 사용 방법
 *   BacktestSessionDialog dlg = new BacktestSessionDialog(parentFrame, userId);
 *   dlg.show();  // 모달
 *   SessionDTO selected = dlg.getSelectedSession();  // null = 취소
 */
public class BacktestSessionDialog extends JDialog {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final String              userId;
    private final BacktestSessionDAO  dao    = new BacktestSessionDAO();
    

    /** 최종 선택/생성된 세션 (null = 취소) */
    private SessionDTO selectedSession = null;
    private List<SessionDTO> currentSessionList = new ArrayList<>();
    // ── UI 컴포넌트 ────────────────────────────────
    private JTable  sessionTable;
    private DefaultTableModel tableModel;

    // 새 세션 입력 필드
    private JTextField  tfSessionName;
    private JTextField  tfStartDate;
    private JTextField  tfSeedMoney;
    private JLabel      lblEndDate;
    private JLabel      lblOverlapWarning;

    // ════════════════════════════════════════════════
    //  생성자
    // ════════════════════════════════════════════════

    public BacktestSessionDialog(JFrame parent, String userId) {
        super(parent, "백테스팅 세션", true);
        this.userId = userId;

        setSize(720, 520);
        setLocationRelativeTo(parent);
        setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        setLayout(new BorderLayout());

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("📋 기존 세션 선택", buildExistingTab());
        tabs.addTab("✚ 새 세션 만들기",  buildCreateTab());

        add(tabs, BorderLayout.CENTER);
        add(buildBottomPanel(), BorderLayout.SOUTH);

        // 기존 세션 목록 로드
        refreshTable();
    }

 // ════════════════════════════════════════════════
    //  탭 1 : 기존 세션 목록 (수정됨)
    // ════════════════════════════════════════════════
    private JPanel buildExistingTab() {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(10, 10, 10, 10));
        panel.setBackground(Color.WHITE);

        String[] cols = {"세션명", "시작 시각", "종료 시각", "현재 진행 시각", "초기 자본"};
        tableModel = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        sessionTable = new JTable(tableModel);
        sessionTable.setRowHeight(28);
        sessionTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionTable.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
        sessionTable.getTableHeader().setFont(new Font("맑은 고딕", Font.BOLD, 13));

        sessionTable.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) onEnterClicked();
            }
        });

        JScrollPane scroll = new JScrollPane(sessionTable);
        panel.add(scroll, BorderLayout.CENTER);

        // --- 하단 안내문 및 삭제 버튼 묶음 패널 추가 ---
        JPanel bottomContainer = new JPanel(new BorderLayout());
        bottomContainer.setBackground(Color.WHITE);
        
        JLabel hint = new JLabel("세션을 선택한 뒤 [입장] 버튼을 누르거나 더블클릭하세요.");
        hint.setForeground(Color.GRAY);
        hint.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        
        JButton btnDelete = new JButton("선택 삭제");
        btnDelete.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        btnDelete.setBackground(new Color(231, 76, 60)); // 빨간색 강조
        btnDelete.setForeground(Color.WHITE);
        btnDelete.setFocusPainted(false);
        btnDelete.addActionListener(e -> onDeleteClicked());

        bottomContainer.add(hint, BorderLayout.WEST);
        bottomContainer.add(btnDelete, BorderLayout.EAST);
        
        panel.add(bottomContainer, BorderLayout.SOUTH);

        return panel;
    }

    // ════════════════════════════════════════════════
    //  탭 2 : 새 세션 만들기
    // ════════════════════════════════════════════════

    private JPanel buildCreateTab() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(new EmptyBorder(15, 20, 15, 20));
        panel.setBackground(Color.WHITE);

        // 세션 이름
        panel.add(makeLabel("세션 이름"));
        tfSessionName = makeTf("나의 백테스팅");
        panel.add(tfSessionName);
        panel.add(Box.createVerticalStrut(12));

        // 시작 날짜
        panel.add(makeLabel("시작 날짜 (yyyy-MM-dd HH:mm)"));
        JPanel dateRow = new JPanel(new BorderLayout(8, 0));
        dateRow.setBackground(Color.WHITE);
        tfStartDate = new JTextField();
        tfStartDate.setFont(new Font("맑은 고딕", Font.PLAIN, 13));

        JButton btnRandom = new JButton("🎲 랜덤");
        btnRandom.setFocusPainted(false);
        btnRandom.addActionListener(e -> fillRandomDate());

        dateRow.add(tfStartDate, BorderLayout.CENTER);
        dateRow.add(btnRandom,   BorderLayout.EAST);
        dateRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        panel.add(dateRow);
        panel.add(Box.createVerticalStrut(6));

        // 종료 날짜 (자동 계산)
        lblEndDate = new JLabel("→ 종료 날짜: (시작 날짜 + 1개월 자동 계산)");
        lblEndDate.setForeground(Color.DARK_GRAY);
        lblEndDate.setFont(new Font("맑은 고딕", Font.ITALIC, 12));
        panel.add(lblEndDate);

        tfStartDate.getDocument().addDocumentListener(new DocumentListener() {
        	           public void insertUpdate(DocumentEvent e)  { updateEndDateLabel(); }
        	           public void removeUpdate(DocumentEvent e)  { updateEndDateLabel(); }
        	           public void changedUpdate(DocumentEvent e) { updateEndDateLabel(); }
        });

        panel.add(Box.createVerticalStrut(12));

        // 초기 자본금
        panel.add(makeLabel("초기 자본금 (KRW)"));
        tfSeedMoney = makeTf("100000000");
        panel.add(tfSeedMoney);
        panel.add(Box.createVerticalStrut(12));

        // 겹침 경고
        lblOverlapWarning = new JLabel(" ");
        lblOverlapWarning.setForeground(Color.RED);
        lblOverlapWarning.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        panel.add(lblOverlapWarning);
        panel.add(Box.createVerticalStrut(12));

        // 생성 버튼
        JButton btnCreate = new JButton("세션 생성");
        btnCreate.setBackground(new Color(52, 152, 219));
        btnCreate.setForeground(Color.WHITE);
        btnCreate.setFont(new Font("맑은 고딕", Font.BOLD, 14));
        btnCreate.setFocusPainted(false);
        btnCreate.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        btnCreate.addActionListener(e -> onCreateClicked());
        panel.add(btnCreate);

        return panel;
    }

    // ════════════════════════════════════════════════
    //  하단 버튼 패널
    // ════════════════════════════════════════════════

    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 8));
        panel.setBackground(new Color(248, 248, 248));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        JButton btnEnter  = new JButton("입장 ▶");
        JButton btnCancel = new JButton("취소");

        styleBtn(btnEnter,  new Color(46, 204, 113), Color.WHITE);
        styleBtn(btnCancel, new Color(236, 240, 241), Color.DARK_GRAY);

        btnEnter.addActionListener(e  -> onEnterClicked());
        btnCancel.addActionListener(e -> dispose());

        panel.add(btnCancel);
        panel.add(btnEnter);
        return panel;
    }

    // ════════════════════════════════════════════════
    //  이벤트 핸들러
    // ════════════════════════════════════════════════

    /** [입장] 버튼 — 선택된 기존 세션으로 진입 */
    private void onEnterClicked() {
        int viewRow = sessionTable.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "세션을 선택해주세요.", "알림", JOptionPane.WARNING_MESSAGE);
            return;
        }
        
        // 🚀 [추가] JTable에서 헤더 정렬 등을 했을 경우를 대비해 View 인덱스를 Model 인덱스로 변환
        int modelRow = sessionTable.convertRowIndexToModel(viewRow);

        // 🚀 [수정] DB에서 새로 조회하지 않고, 테이블을 그릴 때 사용했던 currentSessionList에서 가져옴
        if (modelRow >= 0 && modelRow < currentSessionList.size()) {
            selectedSession = currentSessionList.get(modelRow);
            dispose();
        } else {
            JOptionPane.showMessageDialog(this, "세션 정보를 불러오는데 실패했습니다.", "오류", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** [세션 생성] 버튼 */
    private void onCreateClicked() {
        String name        = tfSessionName.getText().trim();
        String startStr    = tfStartDate.getText().trim();
        String seedStr     = tfSeedMoney.getText().replaceAll(",", "").trim();

        // 입력 검증
        if (name.isEmpty()) {
            showWarn("세션 이름을 입력해주세요."); return;
        }
        if (startStr.isEmpty()) {
            showWarn("시작 날짜를 입력해주세요."); return;
        }

        LocalDateTime startTime;
        try {
            startTime = LocalDateTime.parse(startStr, FMT);
        } catch (Exception ex) {
            showWarn("날짜 형식이 올바르지 않습니다. (yyyy-MM-dd HH:mm)"); return;
        }

     // 최소 1개월 전 검증
        if (startTime.isAfter(LocalDateTime.now().minusMonths(1))) {
            showWarn("시작 날짜는 최소 1개월 이전이어야 합니다."); return;
        }

        // [추가] DB 분봉 데이터 존재 여부 및 하한선 검증
        LocalDateTime earliestMinuteTime = dao.getEarliestCandleTime();
        if (earliestMinuteTime == null) {
            showWarn("DB에 1분봉 데이터가 존재하지 않아 백테스팅 세션을 생성할 수 없습니다."); return;
        }
        if (startTime.isBefore(earliestMinuteTime)) {
            showWarn("해당 날짜에는 분봉 데이터가 존재하지 않습니다.\n(가장 오래된 1분봉: " + earliestMinuteTime.format(FMT) + ")");
            return;
        }

        long seed;
        try {
            seed = Long.parseLong(seedStr);
            if (seed < 10_000) { showWarn("초기 자본금은 10,000원 이상이어야 합니다."); return; }
        } catch (NumberFormatException ex) {
            showWarn("초기 자본금은 숫자만 입력해주세요."); return;
        }

        // 겹침 검사
        if (dao.hasOverlap(userId, startTime)) {
            lblOverlapWarning.setText("⛔ 해당 기간이 기존 세션과 겹칩니다. 다른 날짜를 선택해주세요.");
            return;
        }
        lblOverlapWarning.setText(" ");

        // DB 생성
        Long newSessionId = dao.createSession(userId, name, startTime, seed);
        if (newSessionId == null) {
            showWarn("세션 생성 중 오류가 발생했습니다."); return;
        }
        
        //초기 자본금(KRW)을 assets 테이블에 등록
        boolean isAssetCreated = AssetDAO.createInitialAsset(userId, newSessionId, BigDecimal.valueOf(seed));
        if (!isAssetCreated) {
            showWarn("초기 자본금 등록 중 오류가 발생했습니다.");
            return;
        }

        // 생성 성공 → 바로 해당 세션을 selectedSession 으로 설정하고 닫기
        List<SessionDTO> sessions = dao.getBacktestSessions(userId);
        sessions.stream()
                .filter(s -> s.getSessionId().equals(newSessionId))
                .findFirst()
                .ifPresent(s -> selectedSession = s);

        JOptionPane.showMessageDialog(this, "세션이 생성되었습니다!\n시작: " + startTime.format(FMT),
                "완료", JOptionPane.INFORMATION_MESSAGE);
        dispose();
    }

    // ════════════════════════════════════════════════
    //  랜덤 날짜 생성
    // ════════════════════════════════════════════════

    /**
     * DB에 존재하는 데이터 범위 안에서 기존 세션과 겹치지 않는 랜덤 시작 날짜를 반복 탐색합니다.
     * 최대 30회 시도 후 실패 메시지를 출력합니다.
     */
    private void fillRandomDate() {
LocalDateTime earliest = dao.getEarliestCandleTime();
        
        // 1분봉 데이터가 아예 없는 경우 처리
        if (earliest == null) {
            showWarn("DB에 1분봉 데이터가 존재하지 않습니다."); return;
        }

        // 시작 가능 하한: DB 가장 오래된 날짜 또는 현재 -12개월 중 더 최근
        LocalDateTime lowerBound = earliest.isAfter(LocalDateTime.now().minusMonths(12))
                                   ? earliest
                                   : LocalDateTime.now().minusMonths(12);
        // 시작 가능 상한: 현재 -1개월 (끝이 현재 이전이어야 함)
        LocalDateTime upperBound = LocalDateTime.now().minusMonths(1);

        if (!lowerBound.isBefore(upperBound)) {
            showWarn("DB에 충분한 과거 분봉 데이터가 없습니다."); return;
        }

        long rangeMinutes = java.time.temporal.ChronoUnit.MINUTES.between(lowerBound, upperBound);

        for (int attempt = 0; attempt < 30; attempt++) {
            long offset       = ThreadLocalRandom.current().nextLong(rangeMinutes);
            LocalDateTime candidate = lowerBound.plusMinutes(offset)
                    .withSecond(0).withNano(0);

            if (!dao.hasOverlap(userId, candidate)) {
                tfStartDate.setText(candidate.format(FMT));
                return;
            }
        }
        showWarn("겹치지 않는 랜덤 날짜를 찾지 못했습니다.\n날짜를 직접 입력해주세요.");
    }

    // ════════════════════════════════════════════════
    //  보조 메서드
    // ════════════════════════════════════════════════

    private void refreshTable() {
        tableModel.setRowCount(0);
        
        //조회한 데이터를 지역 변수가 아닌 전역 변수(currentSessionList)에 저장합니다.
        currentSessionList = dao.getBacktestSessions(userId);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

        for (SessionDTO s : currentSessionList) {
            LocalDateTime start   = s.getStartSimTime() != null ? s.getStartSimTime().toLocalDateTime() : null;
            LocalDateTime end     = start != null ? start.plusMonths(1) : null;
            LocalDateTime current = s.getCurrentSimTime() != null ? s.getCurrentSimTime().toLocalDateTime() : start;

            tableModel.addRow(new Object[]{
                s.getSessionName(),
                start   != null ? start.format(f)   : "-",
                end     != null ? end.format(f)      : "-",
                current != null ? current.format(f)  : "-",
                String.format("%,d KRW", s.getInitialSeedMoney() != null ? s.getInitialSeedMoney().longValue() : 0)
            });
        }
    }

    /** [선택 삭제] 버튼 이벤트 */
    private void onDeleteClicked() {
        int viewRow = sessionTable.getSelectedRow();
        if (viewRow < 0) {
            JOptionPane.showMessageDialog(this, "삭제할 세션을 먼저 선택해주세요.", "알림", JOptionPane.WARNING_MESSAGE);
            return;
        }

        int modelRow = sessionTable.convertRowIndexToModel(viewRow);
        SessionDTO targetSession = currentSessionList.get(modelRow);

        // 1. 현재 실행 중인 세션인지 검증
        long activeSessionId = SessionManager.getInstance().getCurrentSessionId();
        if (targetSession.getSessionId() == activeSessionId) {
            JOptionPane.showMessageDialog(this, "현재 실행 중인 세션은 삭제할 수 없습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // 2. 삭제 확인 알림
        int confirm = JOptionPane.showConfirmDialog(this,
                "[" + targetSession.getSessionName() + "] 세션을 정말 삭제하시겠습니까?\n이 작업은 되돌릴 수 없으며, 모든 거래 내역이 함께 삭제됩니다.",
                "세션 삭제",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE);

        if (confirm == JOptionPane.YES_OPTION) {
            boolean isDeleted = dao.deleteSession(targetSession.getSessionId());
            if (isDeleted) {
                JOptionPane.showMessageDialog(this, "세션이 성공적으로 삭제되었습니다.", "완료", JOptionPane.INFORMATION_MESSAGE);
                refreshTable(); // 테이블 데이터 리로드
            } else {
                JOptionPane.showMessageDialog(this, "세션 삭제 중 오류가 발생했습니다.", "오류", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
    
    private void updateEndDateLabel() {
        try {
            LocalDateTime start = LocalDateTime.parse(tfStartDate.getText().trim(), FMT);
            LocalDateTime end   = start.plusMonths(1);
            lblEndDate.setText("→ 종료 날짜: " + end.format(FMT));
            lblEndDate.setForeground(new Color(30, 130, 30));

            // 실시간 겹침 경고
            if (dao.hasOverlap(userId, start)) {
                lblOverlapWarning.setText("⛔ 기존 세션과 기간이 겹칩니다.");
            } else {
                lblOverlapWarning.setText("✅ 사용 가능한 기간입니다.");
                lblOverlapWarning.setForeground(new Color(30, 130, 30));
            }
        } catch (Exception ex) {
            lblEndDate.setText("→ 종료 날짜: (올바른 날짜 형식을 입력하세요)");
            lblEndDate.setForeground(Color.GRAY);
        }
    }

    private JLabel makeLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font("맑은 고딕", Font.BOLD, 12));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private JTextField makeTf(String placeholder) {
        JTextField tf = new JTextField(placeholder);
        tf.setFont(new Font("맑은 고딕", Font.PLAIN, 13));
        tf.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        return tf;
    }

    private void styleBtn(JButton btn, Color bg, Color fg) {
        btn.setBackground(bg);
        btn.setForeground(fg);
        btn.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        btn.setFocusPainted(false);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(110, 36));
    }

    private void showWarn(String msg) {
        JOptionPane.showMessageDialog(this, msg, "입력 오류", JOptionPane.WARNING_MESSAGE);
    }

    // ════════════════════════════════════════════════
    //  공개 API
    // ════════════════════════════════════════════════

    /** 선택/생성된 세션 반환. null = 취소 */
    public SessionDTO getSelectedSession() { return selectedSession; }
}