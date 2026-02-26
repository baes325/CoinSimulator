package Investment_details.Asset;

import DTO.MyAssetStatusDTO;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.table.*;
import java.awt.*;
import java.util.List;

/**
 * 보유자산 목록 테이블 (업비트 스타일)
 *
 * 컬럼: 보유자산 | 보유수량 | 매수평균가(수정) | 매수금액 | 평가금액 | 평가손익(%) | [주문▼]
 */
public class Assets_TablePanel extends JPanel {

    private static final String[] HEADERS = {
        "보유자산", "보유수량", "매수평균가", "매수금액", "평가금액", "평가손익(%)", ""
    };

    private final DefaultTableModel tableModel;
    private final JTable table;

    private static final Color C_PROFIT = new Color(214, 46,  46);
    private static final Color C_LOSS   = new Color(56,  97,  214);
    private static final Color C_LABEL  = new Color(102, 102, 102);
    private static final Color C_VALUE  = new Color(20,  20,  20);
    private static final Color C_DIV    = new Color(224, 224, 224);
    private static final Color C_ROW_ODD  = Color.WHITE;
    private static final Color C_ROW_EVEN = new Color(250, 250, 252);

    public Assets_TablePanel() {
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);

        // 제목 바 ("보유자산 목록" + 소액자산 숨기기 체크박스)
        JPanel titleBar = buildTitleBar();
        add(titleBar, BorderLayout.NORTH);

        // 테이블
        tableModel = new DefaultTableModel(HEADERS, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c)       { return Object.class; }
        };

        table = new JTable(tableModel);
        table.setRowHeight(54);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setBackground(Color.WHITE);
        table.setSelectionBackground(new Color(240, 245, 255));
        table.setSelectionForeground(C_VALUE);
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(true);

        styleHeader();
        applyColumnRenderers();
        setColumnWidths();

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Color.WHITE);
        add(scroll, BorderLayout.CENTER);
    }

    // ── 제목 바 ───────────────────────────────────────────────────
    private JPanel buildTitleBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(Color.WHITE);
        bar.setBorder(new EmptyBorder(10, 20, 8, 14));

        JLabel title = new JLabel("보유자산 목록");
        title.setFont(new Font("맑은 고딕", Font.BOLD, 13));
        title.setForeground(C_VALUE);
        bar.add(title, BorderLayout.WEST);


        return bar;
    }

    // ── 헤더 스타일 ───────────────────────────────────────────────
    private void styleHeader() {
        JTableHeader header = table.getTableHeader();
        header.setBackground(new Color(246, 246, 246));
        header.setForeground(C_LABEL);
        header.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
        header.setPreferredSize(new Dimension(0, 34));
        header.setBorder(new MatteBorder(1, 0, 1, 0, C_DIV));

        // 각 컬럼 헤더 정렬
        DefaultTableCellRenderer centerHdr = headerRenderer(SwingConstants.CENTER);
        DefaultTableCellRenderer rightHdr  = headerRenderer(SwingConstants.RIGHT);

        table.getColumnModel().getColumn(0).setHeaderRenderer(centerHdr);
        table.getColumnModel().getColumn(1).setHeaderRenderer(centerHdr);
        for (int i = 2; i <= 5; i++)
            table.getColumnModel().getColumn(i).setHeaderRenderer(rightHdr);
        table.getColumnModel().getColumn(6).setHeaderRenderer(centerHdr);
    }

    private DefaultTableCellRenderer headerRenderer(int align) {
        DefaultTableCellRenderer r = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setBackground(new Color(246, 246, 246));
                setForeground(C_LABEL);
                setFont(new Font("맑은 고딕", Font.PLAIN, 11));
                setBorder(new MatteBorder(1, 0, 1, 0, C_DIV));
                setHorizontalAlignment(align);
                return this;
            }
        };
        return r;
    }

    // ── 컬럼 렌더러 ───────────────────────────────────────────────
    private void applyColumnRenderers() {
        // 0: 보유자산 (코인명 + 심볼, 좌측 정렬, 아이콘 영역)
        table.getColumnModel().getColumn(0).setCellRenderer(new CoinNameRenderer());

        // 1: 보유수량 (중앙)
        table.getColumnModel().getColumn(1).setCellRenderer(centerCell());

        // 2: 매수평균가 (우측 + 수정 링크)
        table.getColumnModel().getColumn(2).setCellRenderer(new AvgPriceRenderer());

        // 3: 매수금액 (우측)
        table.getColumnModel().getColumn(3).setCellRenderer(rightCell());

        // 4: 평가금액 (우측)
        table.getColumnModel().getColumn(4).setCellRenderer(rightCell());

        // 5: 평가손익(%) (우측 + 색상)
        table.getColumnModel().getColumn(5).setCellRenderer(new PnlRenderer());


    }

    private void setColumnWidths() {
        int[] w = {160, 160, 130, 120, 110, 120, 80};
        for (int i = 0; i < w.length; i++)
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
    }

    // ── 데이터 업데이트 ───────────────────────────────────────────
    public void updateTable(List<MyAssetStatusDTO> list) {
        tableModel.setRowCount(0);
        if (list == null) return;

        for (MyAssetStatusDTO dto : list) {
            double pnlRate = dto.getProfitRate();
            String pnlStr  = String.format("%+.2f %%", pnlRate);

            // 손익금 계산: (현재가 - 평단가) * 보유수량
            long pnlKrw = 0;
            if (dto.getCurrentPrice() != null && dto.getAvgPrice() != null && dto.getBalance() != null) {
                pnlKrw = dto.getCurrentPrice().subtract(dto.getAvgPrice())
                            .multiply(dto.getBalance()).longValue();
            }
            String pnlKrwStr = String.format("%+,d KRW", pnlKrw);

            tableModel.addRow(new Object[]{
            	    dto.getCurrency(),                                             // 0: 코인명
            	    formatCoinQty(dto),                                            // 1: 보유수량
            	    // 2: 매수평균가 (소수점 4자리까지 표시되도록 수정)
            	    dto.getAvgPrice() != null ? String.format("%,.4f KRW", dto.getAvgPrice().doubleValue()) : "0 KRW",  
            	    // 3: 매수금액 (단순 절사 longValue() 대신 반올림 처리 적용)
            	    dto.getAvgPrice() != null && dto.getBalance() != null
            	        ? String.format("%,d KRW", dto.getAvgPrice().multiply(dto.getBalance()).setScale(0, java.math.RoundingMode.HALF_UP).longValue()) : "0 KRW", 
            	    // 4: 평가금액 (반올림 처리)
            	    dto.getTotalValue() != null ? String.format("%,d KRW", dto.getTotalValue().setScale(0, java.math.RoundingMode.HALF_UP).longValue()) : "0 KRW",  
            	    pnlRate + "||" + pnlStr + "||" + pnlKrwStr,                    // 5: 손익 복합 문자열
            	});
        }
        tableModel.fireTableDataChanged();
    }

    private String formatCoinQty(MyAssetStatusDTO dto) {
        if (dto.getBalance() == null) return "0";
        String symbol = dto.getCurrency();
        // 수량 + 심볼 단위
        return String.format("%.8f %s", dto.getBalance().doubleValue(), symbol)
                     .replaceAll("0+$", "0").replaceAll("(\\.[0-9]*[1-9])0+", "$1");
    }

    // ── 커스텀 렌더러들 ───────────────────────────────────────────

    /** 코인명 + 심볼 2줄 */
    static class CoinNameRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JPanel cell = new JPanel(new BorderLayout(10, 0));
            cell.setBackground(row % 2 == 0 ? C_ROW_ODD : C_ROW_EVEN);
            cell.setBorder(new EmptyBorder(0, 16, 0, 6));

            // 아이콘 자리 (동그라미)
            JPanel icon = new JPanel() {
                @Override protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(200, 200, 200));
                    g2.fillOval(0, 0, 32, 32);
                    g2.setColor(Color.WHITE);
                    g2.setFont(new Font("Dialog", Font.BOLD, 11));
                    FontMetrics fm = g2.getFontMetrics();
                    String s = v != null ? v.toString().substring(0, Math.min(1, v.toString().length())) : "?";
                    g2.drawString(s, (32 - fm.stringWidth(s)) / 2, (32 + fm.getAscent()) / 2 - 1);
                }
            };
            icon.setPreferredSize(new Dimension(32, 32));
            icon.setOpaque(false);
            cell.add(icon, BorderLayout.WEST);

            // 이름 (코인 한글명 자리는 심볼로 대체)
            JPanel nameCol = new JPanel();
            nameCol.setLayout(new BoxLayout(nameCol, BoxLayout.Y_AXIS));
            nameCol.setBackground(cell.getBackground());

            JLabel name = new JLabel(v != null ? v.toString() : "");
            name.setFont(new Font("맑은 고딕", Font.BOLD, 12));
            name.setForeground(C_VALUE);

            JLabel sym = new JLabel(v != null ? v.toString() : "");
            sym.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            sym.setForeground(C_LABEL);

            nameCol.add(Box.createVerticalGlue());
            nameCol.add(name);
            nameCol.add(sym);
            nameCol.add(Box.createVerticalGlue());
            cell.add(nameCol, BorderLayout.CENTER);

            if (sel) cell.setBackground(new Color(240, 245, 255));
            return cell;
        }
    }

    /** 매수평균가 + "수정" 링크 */
    static class AvgPriceRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JPanel cell = new JPanel();
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBackground(row % 2 == 0 ? C_ROW_ODD : C_ROW_EVEN);
            cell.setBorder(new EmptyBorder(0, 0, 0, 10));

            JLabel price = new JLabel(v != null ? v.toString() : "0 KRW");
            price.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
            price.setForeground(C_VALUE);
            price.setAlignmentX(Component.RIGHT_ALIGNMENT);

            JLabel edit = new JLabel("수정");
            edit.setFont(new Font("맑은 고딕", Font.PLAIN, 10));
            edit.setForeground(new Color(100, 140, 210));
            edit.setAlignmentX(Component.RIGHT_ALIGNMENT);

            cell.add(Box.createVerticalGlue());
            cell.add(price);
            cell.add(edit);
            cell.add(Box.createVerticalGlue());

            if (sel) cell.setBackground(new Color(240, 245, 255));
            return cell;
        }
    }

    /** 평가손익(%) 셀: % 수익률 + 손익금 2줄 */
    static class PnlRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(
                JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            JPanel cell = new JPanel();
            cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
            cell.setBackground(row % 2 == 0 ? C_ROW_ODD : C_ROW_EVEN);
            cell.setBorder(new EmptyBorder(0, 0, 0, 10));

            String raw = v != null ? v.toString() : "0||0.00 %||0 KRW";
            String[] parts = raw.split("\\|\\|");
            double rate = 0;
            try { rate = Double.parseDouble(parts[0]); } catch (Exception ignore) {}

            Color color = rate > 0 ? C_PROFIT : rate < 0 ? C_LOSS : C_VALUE;

            JLabel rateLbl = new JLabel(parts.length > 1 ? parts[1] : "0.00 %");
            rateLbl.setFont(new Font("맑은 고딕", Font.PLAIN, 12));
            rateLbl.setForeground(color);
            rateLbl.setAlignmentX(Component.RIGHT_ALIGNMENT);

            JLabel krwLbl = new JLabel(parts.length > 2 ? parts[2] : "0 KRW");
            krwLbl.setFont(new Font("맑은 고딕", Font.PLAIN, 11));
            krwLbl.setForeground(color);
            krwLbl.setAlignmentX(Component.RIGHT_ALIGNMENT);

            cell.add(Box.createVerticalGlue());
            cell.add(rateLbl);
            cell.add(krwLbl);
            cell.add(Box.createVerticalGlue());

            if (sel) cell.setBackground(new Color(240, 245, 255));
            return cell;
        }
    }

    /** 주문 버튼 렌더러 */


    private DefaultTableCellRenderer centerCell() {
        return new DefaultTableCellRenderer() {
            { setHorizontalAlignment(CENTER); setFont(new Font("맑은 고딕", Font.PLAIN, 12)); }
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setBackground(row % 2 == 0 ? C_ROW_ODD : C_ROW_EVEN);
                setForeground(C_VALUE);
                if (sel) setBackground(new Color(240, 245, 255));
                return this;
            }
        };
    }

    private DefaultTableCellRenderer rightCell() {
        return new DefaultTableCellRenderer() {
            { setHorizontalAlignment(RIGHT); setFont(new Font("맑은 고딕", Font.PLAIN, 12));
              setBorder(new EmptyBorder(0, 0, 0, 10)); }
            @Override public Component getTableCellRendererComponent(
                    JTable t, Object v, boolean sel, boolean focus, int row, int col) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                setBackground(row % 2 == 0 ? C_ROW_ODD : C_ROW_EVEN);
                setForeground(C_VALUE);
                if (sel) setBackground(new Color(240, 245, 255));
                return this;
            }
        };
    }
}