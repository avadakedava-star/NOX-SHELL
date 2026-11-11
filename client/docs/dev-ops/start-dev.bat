@echo off
REM =============================================================================
REM NOX-SHELL 开发环境启动脚本 (Windows)
REM 使用方式: 双击 start-dev.bat 或在命令行运行
REM =============================================================================

echo ========================================
echo   NOX-SHELL 开发环境启动
echo ========================================
echo.

cd /d "%~dp0\..\"

REM 检查 Node.js
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo ❌ 错误: 未找到 Node.js，请先安装 Node.js 22+
    pause
    exit /b 1
)

REM 检查 npm
where npm >nul 2>nul
if %errorlevel% neq 0 (
    echo ❌ 错误: 未找到 npm
    pause
    exit /b 1
)

REM 检查 Rust
where rustc >nul 2>nul
if %errorlevel% neq 0 (
    echo ❌ 错误: 未找到 Rust，请先安装 Rust
    echo    安装命令: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
    pause
    exit /b 1
)

REM 安装依赖（如果 node_modules 不存在）
if not exist "node_modules" (
    echo 📦 正在安装依赖...
    call npm install
)

echo.
echo 🚀 启动 NOX-SHELL 开发服务器...
echo    按 Ctrl+C 停止
echo.
echo ========================================
echo.

REM 启动 Tauri 开发环境
npm run tauri dev

pause
