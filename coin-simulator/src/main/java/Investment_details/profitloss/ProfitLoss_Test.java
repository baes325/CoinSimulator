//package Investment_details.profitloss;
//
//import javax.swing.*;
//
///**
// * 투자손익 패널 테스트 실행 클래스
// * 
// * 실행 순서:
// * 1. 테스트 데이터 생성 (ProfitLossTestData)
// * 2. 메인 패널 실행 (ProfitLoss_MainPanel)
// */
//public class ProfitLoss_Test {
//    
//    public static void main(String[] args) {
//        
//        // ========================================
//        // Step 1: 테스트 데이터 생성
//        // ========================================
//        System.out.println("=== 테스트 데이터 생성 시작 ===");
//        
//        String testUserId = "user_01";
//        //int testDays = 90; // 30일치 데이터 생성
//        
//        // 테스트 데이터 생성 (이미 데이터가 있으면 스킵하셔도 됩니다)
//        //ProfitLossTestData.generateTestData(testUserId, testDays);
//        
//        System.out.println("=== 테스트 데이터 생성 완료 ===\n");
//        
//        // ========================================
//        // Step 2: UI 실행
//        // ========================================
//        SwingUtilities.invokeLater(() -> {
//            JFrame frame = new JFrame("투자손익 테스트 - " + testUserId);
//            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
//            frame.setSize(1000, 700);
//            frame.setLocationRelativeTo(null);
//            
//            // 메인 패널 생성
//            ProfitLoss_MainPanel panel = new ProfitLoss_MainPanel(testUserId);
//            frame.add(panel);
//            
//            frame.setVisible(true);
//            
//            System.out.println("=== UI 창이 열렸습니다 ===");
//        });
//    }
//}
