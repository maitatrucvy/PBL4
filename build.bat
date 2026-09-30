@echo off
REM ============================================================
REM  Build script cho LAN File Sync (không cần Maven)
REM  Yêu cầu: Java 17+ đã cài, script tự tải thư viện
REM ============================================================

setlocal

set "PROJECT_DIR=%~dp0"
set "LIB_DIR=%PROJECT_DIR%lib"
set "SRC_DIR=%PROJECT_DIR%src\main\java"
set "OUT_DIR=%PROJECT_DIR%out\classes"
set "DIST_DIR=%PROJECT_DIR%dist"

echo =====================================================
echo   LAN File Sync Build Script
echo =====================================================

REM Tạo thư mục cần thiết
mkdir "%LIB_DIR%" 2>nul
mkdir "%OUT_DIR%" 2>nul
mkdir "%DIST_DIR%" 2>nul

REM Tải thư viện nếu chưa có
if not exist "%LIB_DIR%\gson-2.10.1.jar" (
    echo [1/3] Dang tai Gson...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/google/code/gson/gson/2.10.1/gson-2.10.1.jar' -OutFile '%LIB_DIR%\gson-2.10.1.jar' -UseBasicParsing"
)

if not exist "%LIB_DIR%\sqlite-jdbc-3.45.1.0.jar" (
    echo [2/3] Dang tai SQLite JDBC...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.45.1.0/sqlite-jdbc-3.45.1.0.jar' -OutFile '%LIB_DIR%\sqlite-jdbc-3.45.1.0.jar' -UseBasicParsing"
)

if not exist "%LIB_DIR%\flatlaf-3.4.jar" (
    echo [3/3] Dang tai FlatLaf...
    powershell -Command "Invoke-WebRequest -Uri 'https://repo1.maven.org/maven2/com/formdev/flatlaf/3.4/flatlaf-3.4.jar' -OutFile '%LIB_DIR%\flatlaf-3.4.jar' -UseBasicParsing"
)

set "CLASSPATH=%LIB_DIR%\gson-2.10.1.jar;%LIB_DIR%\sqlite-jdbc-3.45.1.0.jar;%LIB_DIR%\flatlaf-3.4.jar"

REM Tìm tất cả file Java
echo.
echo [Compile] Dang bien dich...
dir /s /b "%SRC_DIR%\*.java" > "%TEMP%\sources.txt"
javac -encoding UTF-8 -cp "%CLASSPATH%" -d "%OUT_DIR%" @"%TEMP%\sources.txt"

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Bien dich that bai!
    pause
    exit /b 1
)
echo [Compile] Thanh cong!

REM Build Server JAR
echo.
echo [JAR] Dang dong goi Server...
cd /d "%OUT_DIR%"
echo Main-Class: com.lansync.server.ServerMain > "%TEMP%\manifest_server.txt"
echo Class-Path: lib/gson-2.10.1.jar lib/sqlite-jdbc-3.45.1.0.jar lib/flatlaf-3.4.jar >> "%TEMP%\manifest_server.txt"
jar cfm "%DIST_DIR%\LanFileSync-Server.jar" "%TEMP%\manifest_server.txt" .

REM Build Client JAR
echo [JAR] Dang dong goi Client...
echo Main-Class: com.lansync.client.ClientMain > "%TEMP%\manifest_client.txt"
echo Class-Path: lib/gson-2.10.1.jar lib/sqlite-jdbc-3.45.1.0.jar lib/flatlaf-3.4.jar >> "%TEMP%\manifest_client.txt"
jar cfm "%DIST_DIR%\LanFileSync-Client.jar" "%TEMP%\manifest_client.txt" .

cd /d "%PROJECT_DIR%"

REM Copy lib vào dist
xcopy /Y /Q "%LIB_DIR%\*.jar" "%DIST_DIR%\lib\" >nul 2>&1

echo.
echo =====================================================
echo   Build HOAN THANH!
echo   Server: dist\LanFileSync-Server.jar
echo   Client: dist\LanFileSync-Client.jar
echo   Chay:   java -jar dist\LanFileSync-Server.jar
echo           java -jar dist\LanFileSync-Client.jar
echo =====================================================
pause
