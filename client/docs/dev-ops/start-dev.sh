#!/bin/bash

# =============================================================================
# NOX-SHELL 开发环境启动脚本
# 使用方式: ./start-dev.sh
# =============================================================================

set -e

# 获取脚本所在目录
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"

echo "========================================"
echo "  NOX-SHELL 开发环境启动"
echo "========================================"
echo ""

# 检查 Node.js
if ! command -v node &> /dev/null; then
    echo "❌ 错误: 未找到 Node.js，请先安装 Node.js 22+"
    exit 1
fi

# 检查 npm
if ! command -v npm &> /dev/null; then
    echo "❌ 错误: 未找到 npm"
    exit 1
fi

# 检查 Rust
if ! command -v rustc &> /dev/null; then
    echo "❌ 错误: 未找到 Rust，请先安装 Rust"
    echo "   安装命令: curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh"
    exit 1
fi

# 检查 Tauri CLI
if ! command -v tauri &> /dev/null; then
    echo "📦 正在安装 Tauri CLI..."
    cd "$PROJECT_ROOT"
    npm install -D @tauri-apps/cli
fi

# 进入项目目录
cd "$PROJECT_ROOT"

# 安装依赖（如果 node_modules 不存在）
if [ ! -d "node_modules" ]; then
    echo "📦 正在安装依赖..."
    npm install
fi

echo ""
echo "🚀 启动 NOX-SHELL 开发服务器..."
echo "   按 Ctrl+C 停止"
echo ""
echo "========================================"
echo ""

# 启动 Tauri 开发环境
npm run tauri dev
