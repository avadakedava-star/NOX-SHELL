@echo off
setlocal
chcp 65001 >nul
title final-test
cd /d "%~dp0"
echo 正在启动 final-test，请稍等……
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-local.ps1" %*
if errorlevel 1 (
    echo.
    echo 启动失败。请查看上面的错误信息。
    pause
    exit /b 1
)
echo.
echo 启动完成。可以关闭这个窗口，前后端仍会继续运行。
timeout /t 5 >nul 2>nul
exit /b 0
