package com.team.coin_simulator.backtest;

/**
 * ============================================================
 *  MainFrame 통합 가이드
 *  - 아래 코드 조각들을 기존 MainFrame.java에 붙여넣는 방법 설명
 * ============================================================
 *
 *  [1] 필드 추가 (MainFrame 클래스 내부)
 * -----------------------------------------------------------
 *
 *     private BacktestTimeControlPanel backtestControlPanel;
 *     private CandleChartBacktestAdapter chartBacktestAdapter;
 *
 *
 *  [2] initComponents() 수정 — 상단 패널 교체
 * -----------------------------------------------------------
 *
 *     기존:
 *         timeControlPanel = new TimeControlPanel();
 *         topPanel.add(timeControlPanel, BorderLayout.CENTER);
 *
 *     변경:
 *         // 기존 TimeControlPanel 은 그대로 두거나, 탭으로 분리해도 됩니다.
 *         // 여기서는 BacktestTimeControlPanel 을 CENTER 에 추가하는 예시입니다.
 *
 *         backtestControlPanel = new BacktestTimeControlPanel(this, currentUserId);
 *         topPanel.add(backtestControlPanel, BorderLayout.CENTER);
 *
 *
 *  [3] createTradingPanel() 이후 어댑터 연결
 * -----------------------------------------------------------
 *
 *     tradingPanel 을 생성한 뒤 (chartPanel, historyPanel 이 준비된 후):
 *
 *         chartBacktestAdapter = new CandleChartBacktestAdapter(chartPanel, historyPanel);
 *         BacktestTimeController.getInstance().addTickListener(chartBacktestAdapter);
 *
 *
 *  [4] simulation_sessions 테이블 스키마 확인 (end_sim_time 컬럼 추가 필요)
 * -----------------------------------------------------------
 *
 *     기존 테이블에 end_sim_time 컬럼이 없다면 아래 ALTER 를 실행하세요:
 *
 *     ALTER TABLE simulation_sessions
 *         ADD COLUMN end_sim_time DATETIME NULL COMMENT '백테스팅 종료 시각 (start + 1month)';
 *
 *
 *  [5] 패키지 구조
 * -----------------------------------------------------------
 *
 *     src/main/java/
 *       com/team/coin_simulator/
 *         backtest/
 *           BacktestSpeed.java                  ← 배속 enum
 *           BacktestSessionDAO.java             ← 세션 DB 조작
 *           BacktestTimeController.java         ← 핵심 시간 엔진
 *           BacktestSessionDialog.java          ← 세션 생성/선택 UI
 *           BacktestTimeControlPanel.java       ← 배속 제어 UI 패널
 *           CandleChartBacktestAdapter.java     ← 차트·마켓 패널 연결 어댑터
 *
 *
 *  [6] Thread.ofVirtual() 사용 시 Java 21+ 필요
 * -----------------------------------------------------------
 *
 *     Java 17 환경이라면 Thread.ofVirtual().start(...) 를 아래처럼 교체하세요:
 *
 *         new Thread(() -> { ... }).start();
 *
 *     또는 pom.xml 의 Java 버전을 21로 올리세요:
 *         <maven.compiler.source>21</maven.compiler.source>
 *         <maven.compiler.target>21</maven.compiler.target>
 *
 *
 *  [7] DocumentAdapter 미존재 시
 * -----------------------------------------------------------
 *
 *     javax.swing.event.DocumentAdapter 는 존재하지 않습니다.
 *     BacktestSessionDialog 의 addDocumentListener 부분을
 *     아래 익명 클래스로 교체하세요:
 *
 *         tfStartDate.getDocument().addDocumentListener(new DocumentListener() {
 *             public void insertUpdate(DocumentEvent e)  { updateEndDateLabel(); }
 *             public void removeUpdate(DocumentEvent e)  { updateEndDateLabel(); }
 *             public void changedUpdate(DocumentEvent e) { updateEndDateLabel(); }
 *         });
 *
 *     그리고 import 에 아래 두 줄 추가:
 *         import javax.swing.event.DocumentListener;
 *         import javax.swing.event.DocumentEvent;
 *
 * ============================================================
 */
public class MainFrameIntegrationGuide {
    // 이 파일은 통합 가이드 전용입니다. 직접 실행하지 않습니다.
    private MainFrameIntegrationGuide() {}
}