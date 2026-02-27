package com.team.coin_simulator.Market_Panel;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import javax.swing.BoxLayout;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField; // 검색창 컴포넌트
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.MatteBorder;
import javax.swing.event.DocumentEvent; // 검색 이벤트
import javax.swing.event.DocumentListener; // 검색 리스너

import com.team.coin_simulator.CoinConfig;

import DAO.AssetDAO;
import DAO.UpbitWebSocketDao;
import DAO.WatchListDAO;
import DTO.AssetDTO;
import DTO.WatchlistDTO;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public class HistoryPanel extends JPanel implements UpbitWebSocketDao.TickerListener {
	
	String loginUser = "test_user1";
    
    // UI 컴포넌트 선언
    JPanel coinHeadPanel, tabPanel;
    JPanel searchPanel; // [추가] 검색 패널
    JTextField searchField; // [추가] 검색 텍스트 필드
    JScrollPane scrollPanel;
    
    // 리스트 전환을 위한 컴포넌트
    JPanel mainListPanel; 
    CardLayout cardLayout; 
    private AssetDAO assetDAO = new AssetDAO();
    
    JPanel allCoinPanel, ownedCoinPanel, interestCoinPanel;

    private String selectedCoinSymbol = null;
    
    private CoinRowPanel selectedRowPanel = null; 
    private final Color SELECTED_COLOR = new Color(240, 240, 240); 
    private final Color DEFAULT_COLOR = Color.WHITE; 

    // 데이터 관리
    private HashMap<String, List<CoinRowPanel>> coinMap = new HashMap<>();
    private List<TabButton> tabButtons = new ArrayList<>();

    // [추가] 코인 선택 리스너 목록
    private List<CoinSelectionListener> coinSelectionListeners = new ArrayList<>();
    
    private long sessionId;
    /**
     * 코인 선택 리스너 인터페이스
     */
    public interface CoinSelectionListener {
        void onCoinSelected(String coinSymbol);
    }
    
    /**
     * 코인 선택 리스너 추가
     */
    public void addCoinSelectionListener(CoinSelectionListener listener) {
        coinSelectionListeners.add(listener);
    }
    
    /**
     * 코인 선택 이벤트 발생
     */
    private void fireCoinSelectionEvent(String coinSymbol) {
        for (CoinSelectionListener listener : coinSelectionListeners) {
            listener.onCoinSelected(coinSymbol);
        }
    }
    
    public HistoryPanel(String loginUser, long sessionId) {
        this.loginUser = loginUser;
        this.sessionId = sessionId;
        setLayout(new BorderLayout());
        setBackground(Color.WHITE);
        setMinimumSize(new Dimension(300, 500));
        
        // --- [추가] 0. 검색 패널 구성 ---
        searchPanel = new JPanel(new BorderLayout());
        searchPanel.setBackground(Color.WHITE);
        // 안쪽 여백을 주어 입력창이 너무 꽉 차지 않게 함
        searchPanel.setBorder(new CompoundBorder(
        		new MatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY),
        		new EmptyBorder(5, 5, 5, 5)));
        
        searchField = new JTextField();
        searchField.setPreferredSize(new Dimension(100, 30));
        searchField.setToolTipText("코인명(리플) 또는 심볼(XRP) 검색");
        
        // 검색 이벤트 리스너 등록 (타이핑 할 때마다 실행)
        searchField.getDocument().addDocumentListener(new DocumentListener() {
			@Override
			public void insertUpdate(DocumentEvent e) { filterCoinList(); }
			@Override
			public void removeUpdate(DocumentEvent e) { filterCoinList(); }
			@Override
			public void changedUpdate(DocumentEvent e) { filterCoinList(); }
		});
        
        searchPanel.add(searchField, BorderLayout.CENTER);


        // 1. 탭 버튼 패널 구성
        tabPanel = new JPanel(new GridLayout(1, 3, 5, 3)); 
        tabPanel.setBackground(Color.WHITE); 
        tabPanel.setBorder(new MatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        
        
        TabButton btnAll = new TabButton("전체 코인");
        TabButton btnOwned = new TabButton("보유 코인");
        TabButton btnInterest = new TabButton("관심 코인");
        
        tabButtons.add(btnAll);
        tabButtons.add(btnOwned);
        tabButtons.add(btnInterest);
        
        tabPanel.add(btnAll);
        tabPanel.add(btnOwned);
        tabPanel.add(btnInterest);
        
        // 2. 헤더 패널 구성 (고정 영역)
        coinHeadPanel = new JPanel(new GridLayout(1, 4));
        coinHeadPanel.setBackground(Color.white);
        coinHeadPanel.setBorder(new MatteBorder(0, 0, 1, 0, Color.LIGHT_GRAY));
        coinHeadPanel.add(new JLabel("코인명", SwingConstants.CENTER));
        coinHeadPanel.add(new JLabel("현재가", SwingConstants.CENTER));
        coinHeadPanel.add(new JLabel("전일대비", SwingConstants.CENTER));
        coinHeadPanel.add(new JLabel("거래대금", SwingConstants.CENTER)); 
        
        // 3. 리스트 컨테이너 구성 (CardLayout 적용)
        cardLayout = new CardLayout();
        mainListPanel = new JPanel(cardLayout);
        
        // 3-1. 각 탭에 들어갈 패널 생성 및 설정
        allCoinPanel = createListPanel();
        ownedCoinPanel = createListPanel();
        interestCoinPanel = createListPanel();
        
        // 3-2. CardLayout에 패널 추가 (식별자 부여)
        mainListPanel.add(allCoinPanel, "ALL");	
        mainListPanel.add(ownedCoinPanel, "OWNED");
        mainListPanel.add(interestCoinPanel, "INTEREST");
        
        // 4. 스크롤 패널에 mainListPanel 장착
        scrollPanel = new JScrollPane(mainListPanel);
        scrollPanel.setBorder(null);
        
        
        setupTabActions(btnAll, "ALL");
        setupTabActions(btnOwned, "OWNED");
        setupTabActions(btnInterest, "INTEREST");
        
        // 6. 메인 패널에 부착
        JPanel topContainer = new JPanel();
        // [변경] 상단 레이아웃을 Y_AXIS 박스 레이아웃으로 변경하여 검색창->탭->헤더 순서로 적재
        topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
        
        topContainer.add(searchPanel);   // 1. 검색창
        topContainer.add(tabPanel);      // 2. 탭
        topContainer.add(coinHeadPanel); // 3. 헤더 컬럼
        
        add(topContainer, BorderLayout.NORTH); // 묶은 패널을 북쪽에 배치
        add(scrollPanel, BorderLayout.CENTER);         // 중앙 리스트
        
        
        //코인 데이터 추가
        initDataAndWebSocket();
        loadWatchlist();
    }
    
    @Override
    public void onTickerUpdate(String symbol, String priceStr, String flucStr, String accPriceStr, String tradeVolumeStr) {
        // 기존의 updateCoinPrice 로직을 여기서 수행
        // 화면 리스트 업데이트에는 거래량 데이터가 필요 없으므로 기존 파라미터 4개만 넘깁니다.
        updateCoinPrice(symbol, priceStr, flucStr, accPriceStr);
    }
    // [추가] 검색 필터링 로직
    private void filterCoinList() {
    	String text = searchField.getText().trim();
    	// 대소문자 무시를 위해 대문자로 변환 (코인 심볼이 보통 대문자이므로)
    	String query = text.toUpperCase();
    	
    	// coinMap에 등록된 모든 코인(키:심볼)을 순회
    	for (String symbol : coinMap.keySet()) {
    		// 1. 코인 한글명 가져오기
    		String krName = CoinConfig.COIN_INFO.getOrDefault(symbol, "");
    		
    		// 2. 검색어 매칭 여부 확인 (심볼 포함 OR 한글명 포함)
    		// text가 비어있으면 true(전체 보임)
    		boolean isMatch = text.isEmpty() || symbol.contains(query) || krName.contains(text);
    		
    		// 3. 해당 심볼에 연결된 모든 패널(전체/보유/관심)의 가시성(Visible) 설정
    		List<CoinRowPanel> rows = coinMap.get(symbol);
    		if(rows != null) {
    			for(CoinRowPanel row : rows) {
    				row.setVisible(isMatch);
    			}
    		}
		}
    	
    	// 레이아웃 갱신 (빈 공간 재정렬을 위해 필요할 수 있음)
    	// mainListPanel의 현재 보이는 컴포넌트를 다시 그려줌
    	mainListPanel.revalidate();
    	mainListPanel.repaint();
    }
    
    
    public String getSelectedCoin() {
        return selectedCoinSymbol;
    }
    
    private void selectCoin(String symbol, CoinRowPanel clickedPanel) {
        // 이전 선택 해제
        if (selectedRowPanel != null) {
            selectedRowPanel.setBackground(DEFAULT_COLOR);
        }

        // 새 선택 설정
        this.selectedCoinSymbol = symbol;
        this.selectedRowPanel = clickedPanel;
        
        if (selectedRowPanel != null) {
            selectedRowPanel.setBackground(SELECTED_COLOR);
            selectedRowPanel.setOpaque(true); 
        }

        // [추가] 코인 선택 이벤트 발생
        fireCoinSelectionEvent(symbol);
        
        System.out.println("선택된 코인: " + symbol); 

        revalidate();
        repaint();
    }
    
    
    private void updateTabStyle(TabButton selectedBtn) {
        for (TabButton btn : tabButtons) {
            if (btn == selectedBtn) {
                btn.setSelected(true);
            } else {
                btn.setSelected(false);
            }
        }
    }
    
    private void updateHeadPanel(String type) {
        coinHeadPanel.removeAll(); 

        String[] headers;
        if (type.equals("OWNED")) {
            headers = new String[]{"코인명", "평단가", "수익률", "보유갯수"};
        } else {
            headers = new String[]{"코인명", "현재가", "전일대비", "거래대금"};
        }

        for (String title : headers) {
            coinHeadPanel.add(new JLabel(title, SwingConstants.CENTER));
        }

        coinHeadPanel.revalidate();
        coinHeadPanel.repaint();
        
        // [추가] 탭 전환 시에도 현재 검색어가 유지된 상태로 필터링을 다시 적용
        filterCoinList();
    }
    
    private void setupTabActions(TabButton btn, String cardName) {
        btn.addActionListener(e -> {
            cardLayout.show(mainListPanel, cardName);
            
            updateTabStyle(btn);
            
            updateHeadPanel(cardName); 
        });
    }
    
    private JPanel createListPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBackground(Color.white);
        return panel;
    }
  
    
    
    public void loadOwnedAssets() {
        // [수정 핵심] 1. 기존 coinMap에서 AssetRowPanel 인스턴스만 찾아서 제거 (메모리 누수 및 좀비 업데이트 방지)
        for (List<CoinRowPanel> panelList : coinMap.values()) {
            panelList.removeIf(panel -> panel instanceof AssetRowPanel);
        }
        
        ownedCoinPanel.removeAll();
        
        List<AssetDTO> assets = assetDAO.getUserAssets(loginUser, sessionId);

        if (assets.isEmpty()) {
            ownedCoinPanel.add(new JLabel("보유 중인 코인이 없습니다.", SwingConstants.CENTER));
        } else {
            for (AssetDTO asset : assets) {
                String symbol = asset.getCurrency(); 
                String krName = CoinConfig.COIN_INFO.getOrDefault(symbol, symbol);
                
                String displayName = "<html><center>" + krName + "<br><font size='3' color='gray'>" + symbol + "</font></center></html>";

                AssetRowPanel row = new AssetRowPanel(displayName, asset);
                ownedCoinPanel.add(row);
                
                // coinMap에 새로 추가
                coinMap.computeIfAbsent(symbol, k -> new ArrayList<>()).add(row);
            }
        }
        
        ownedCoinPanel.revalidate();
        ownedCoinPanel.repaint();
    }

    
    public void addNewCoin(int type, String code, String price, String fluc,String Acc_trade_price) {
        String krName = CoinConfig.COIN_INFO.getOrDefault(code, code);

        String displayName = "<html><center>" + krName + "<br><font size='3' color='gray'>" + code + "</font></center></html>";
        
        CoinRowPanel row = new CoinRowPanel(displayName, price, fluc,Acc_trade_price);
        row.setBackground(DEFAULT_COLOR);
        
        row.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
            	selectCoin(code, row); 
            }
        });
        
        switch (type) {
            case 0: allCoinPanel.add(row); break;
            case 1: interestCoinPanel.add(row); break;
        }
        coinMap.computeIfAbsent(code, k -> new ArrayList<>()).add(row);
        
        revalidate();
        repaint();
    }
    

    private void initDataAndWebSocket() {
        // 1. 코인 목록 UI 초기화 (기존 유지)
        for (String code : CoinConfig.getCodes()) {
            addNewCoin(0, code, "Loading...", "0.00", "0");
        }
        // 싱글톤 DAO에 리스너 등록
        UpbitWebSocketDao.getInstance().addListener(this);
        // 보유 자산 로드
        loadOwnedAssets();
        
        // --- 변경된 부분 ---
        loadWatchlist(); // 기존 watchList() 대신 호출
    }
    
    
 // 기존 watchList() 대신 아래 메서드 사용
    public void loadWatchlist() {
        // 1. 기존 관심 코인 패널에 있던 컴포넌트들을 coinMap에서 안전하게 제거 (메모리 누수 방지)
        for (java.awt.Component comp : interestCoinPanel.getComponents()) {
            if (comp instanceof CoinRowPanel) {
                for (List<CoinRowPanel> panelList : coinMap.values()) {
                    panelList.remove(comp);
                }
            }
        }
        
        // 2. 패널 비우기
        interestCoinPanel.removeAll();
        
        // 3. DB에서 관심 코인 목록 불러오기
        WatchListDAO dao = new WatchListDAO();
        List<WatchlistDTO> dbWatchlist = dao.getWatchlistByUser(loginUser);

        for (WatchlistDTO dto : dbWatchlist) {
            // "KRW-BTC" 에서 "BTC"만 추출하여 차트 및 웹소켓과 심볼 동기화
            String market = dto.getMarket();
            String symbol = market.startsWith("KRW-") ? market.substring(4) : market;
            
            // 관심 코인 탭(type: 1)에 추가
            addNewCoin(1, symbol, "Loading...", "0.00", "0");
        }
        
        // 4. UI 새로고침
        interestCoinPanel.revalidate();
        interestCoinPanel.repaint();
    }
    
   
    public void updateCoinPrice(String symbol, String newPrice, String newFluc, String Acc_trade_price) {
        SwingUtilities.invokeLater(() -> {
            if (coinMap.containsKey(symbol)) {
                List<CoinRowPanel> rows = coinMap.get(symbol);
                for (CoinRowPanel row : rows) {
                    row.updateData(newPrice, newFluc, Acc_trade_price); 
                }
            }
        });
    }
    
    public void setSessionId(long newSessionId) {
        this.sessionId = newSessionId;
        loadOwnedAssets(); // 자산 목록 다시 불러오기
    }
    
//    public static void main(String[] args) {
//        JFrame frame = new JFrame("코인 리스트 판넬");
//        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//        
//        HistoryPanel historyPanel = new HistoryPanel();
//        frame.add(historyPanel);
//        
//        frame.pack();
//        frame.setMinimumSize(historyPanel.getMinimumSize()); 
//        frame.setSize(400, 600);
//        frame.setVisible(true);
//        UpbitWebSocketDao.getInstance().start();
//
//    }
}