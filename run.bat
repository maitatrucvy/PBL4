@echo off
REM ============================================================
REM  Script chạy nhanh Server và Client
REM  Yêu cầu: build.bat đã chạy thành công
REM ============================================================

echo Chon che do chay:
echo   1. Chay Server
echo   2. Chay Client
echo   3. Chay ca hai (Server + Client)
echo.
set /p choice="Nhap lua chon (1/2/3): "

set "DIST=dist"
set "LIB=%DIST%\lib"
set "CP=%LIB%\gson-2.10.1.jar;%LIB%\sqlite-jdbc-3.45.1.0.jar;%LIB%\flatlaf-3.4.jar"

if "%choice%"=="1" (
    echo Dang khoi dong Server...
    java -cp "%DIST%\LanFileSync-Server.jar;%CP%" com.lansync.server.ServerMain
) else if "%choice%"=="2" (
    echo Dang khoi dong Client...
    java -cp "%DIST%\LanFileSync-Client.jar;%CP%" com.lansync.client.ClientMain
) else if "%choice%"=="3" (
    echo Dang khoi dong Server va Client...
    start "Server" java -cp "%DIST%\LanFileSync-Server.jar;%CP%" com.lansync.server.ServerMain
    timeout /t 2 /nobreak > nul
    start "Client" java -cp "%DIST%\LanFileSync-Client.jar;%CP%" com.lansync.client.ClientMain
) else (
    echo Lua chon khong hop le!
)
pause
