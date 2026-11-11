# NOX-SHELL 开发环境部署

## 快速启动

根据你的操作系统，双击或运行对应的脚本：

| 操作系统 | 脚本 | 命令 |
|---------|------|------|
| macOS / Linux | `start-dev.sh` | `./start-dev.sh` |
| Windows | `start-dev.bat` | 双击或 `start-dev.bat` |

## 前置要求

确保已安装以下依赖：

### 1. Node.js 22+
- macOS/Linux: [https://nodejs.org/](https://nodejs.org/)
- Windows: 使用 [nvm-windows](https://github.com/coreybutler/nvm-windows) 或官网下载

### 2. Rust
```bash
# macOS / Linux / WSL
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

# Windows: 从 https://rustup.rs 下载安装
```

### 3. Tauri CLI（可选，已在脚本中自动安装）
```bash
npm install -D @tauri-apps/cli
```

## 脚本功能

1. **环境检查** - 检测 Node.js、npm、Rust 是否已安装
2. **依赖安装** - 自动安装 npm 依赖
3. **启动开发服务器** - 运行 `npm run tauri dev`

## 常见问题

**Q: 脚本没有执行权限？**
```bash
chmod +x start-dev.sh
```

**Q: Windows 下提示 "不是内部或外部命令"？**
确保在 Windows Terminal 或 CMD 中运行脚本。

**Q: Rust 安装慢？**
使用国内镜像：
```bash
export RUSTUP_DIST_SERVER=https://mirrors.ustc.edu.cn/rust-static
export RUSTUP_UPDATE_ROOT=https://mirrors.ustc.edu.cn/rust-static/rustup
curl --proto '=https' --tlsv1.2 -sSf https://mirrors.ustc.edu.cn/rust-static/rustup-init.sh | sh
```
