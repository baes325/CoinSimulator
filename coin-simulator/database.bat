@echo off
chcp 65001 > nul
title Crypto Trading Simulator - Database Sync

REM 1. 배치 파일이 위치한 경로로 작업 디렉토리를 강제로 맞춤 (경로 꼬임 방지)
cd /d "%~dp0"

echo 백그라운드 데이터베이스 동기화를 단독 실행합니다...

REM 2. JAR 파일이 실제로 빌드되어 있는지 사전 체크
if not exist "target\coin-simulator-1.0-SNAPSHOT.jar" (
    echo.
    echo [에러] target 폴더에 JAR 파일이 없습니다! 
    echo IDE의 Maven 탭에서 'clean' 후 'package'를 먼저 더블클릭하여 빌드해주세요.
    echo.
    pause
    exit /b
)

REM 3. 정확한 경로의 JAR를 클래스패스로 잡고 타겟 클래스(.java 제외) 실행
java -cp target\coin-simulator-1.0-SNAPSHOT.jar databasetestdata.DownloadDatabase

pause