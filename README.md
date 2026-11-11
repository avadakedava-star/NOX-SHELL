# NOX-SHELL Agent

SSH 智能运维助手，包含 React / TypeScript 前端与 Java 17 / Spring Boot 后端。

## 功能

- 流式对话、真实工具执行审计和明确的请求结束事件。
- SSH 命令执行、危险操作黑名单、同一终端排队。
- 持久化后台长任务、容器部署、就绪检查、任务恢复与取消。
- 任务进度卡、异常中文说明，以及前端连接状态与后台启动改进。
- NOX-SHELL 前端改版、可调整面板，以及明确的服务和 SSH 连接提示。
- 普通/增强模式切换，重连后同步终端上下文，聊天发送入口统一绑定终端。
- 根据执行证据观察进展，停滞时先核查再暂停；模型进展报告须引用真实执行记录。

## 目录

- `client/`：前端及可选 Tauri 桌面项目。
- `server/`：后端 Maven 多模块工程。
- `server/docs/dev-ops/mysql/sql/nox-shell.sql`：数据库表结构，不包含原环境数据。
- `长任务执行规则.md`、`流式请求收尾规则.md`：执行规则和代码入口。

## 本地运行

需要 JDK 17、Maven、Node.js 以及 MySQL。先创建 `nox_shell` 数据库并导入上述 SQL。

在 PowerShell 中设置环境变量（`.env.example` 只是示例，不会自动加载）：

```powershell
$env:DEEPSEEK_API_KEY = "你自己的模型密钥"
$env:MODEL_BASE_URL = "你的 OpenAI 兼容模型接口基础地址"
$env:MODEL_NAME = "该接口支持的模型名称"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "你自己的数据库密码"
$env:NOX_SHELL_SECRET_KEY = "你自己生成并妥善保存的随机32位密钥"
```

默认数据库地址为 `127.0.0.1:3307/nox_shell`，可用 `DB_URL` 覆盖。

从旧版本升级时，若继续使用原数据库，请把 `DB_URL` 指向原有数据库；改名不会自动迁移数据库。将原加密密钥的值配置给 `NOX_SHELL_SECRET_KEY`，不要重新生成，否则已有加密数据无法解密。过渡期间也可继续使用旧环境变量 `WALISSH_SECRET_KEY`，两者同时设置时优先使用新变量。原先自定义的 `walissh.*` 配置需要改为 `nox-shell.*`。

新远端作业使用 `~/.nox-shell-agent/jobs/`；查询已有作业时也会查找旧的 `~/.walissh-agent/jobs/`。前端暂未更名，后端继续兼容它使用的 `walissh-user`，旧会话的用户标识不自动迁移。

```powershell
cd client
npm ci
npm run build
cd ../server
mvn -s ../maven-settings.xml -DskipTests package
cd ..
./start-local.ps1
```

前端地址为 `http://127.0.0.1:5173/`，后端端口为 `8091`。`maven-settings.xml` 与启动脚本默认使用本机 `7897` 代理，按实际环境调整。后台前端使用 Windows 任务计划托管；也可在 `client` 中手动运行 `npm run dev`。

模型地址和名称通过 `MODEL_BASE_URL`、`MODEL_NAME` 配置，配置入口为 `server/nox-shell-server-app/src/main/resources/agent/ssh-agent.yml`。可选手工集成测试需要自行配置模型、SSH 和数据库环境。

## 公开版本说明

本仓库是 `final-test` 的脱敏源码副本：不包含运行日志、数据库备份或历史记录、真实密钥、SSH 凭据、依赖目录及构建产物。原有固定密钥已改为必须通过环境变量配置。

当前保留本地固定用户模式，完整的生产身份认证与资源授权尚未实现。

## 本次更新与验证

详见 [更新说明](docs/updates/2026-11-11-final-test.md)。前端可运行 `npm run test:terminal-binding`、`npm run test:execution-state` 和 `npm run test:chat-mode`。这些测试使用模拟请求，不会连接模型或服务器。后端手工集成测试仍需要自行配置环境。

## 来源与致谢

项目基于小傅哥 / bugstack.cn 的 WaliSSH 与 DDD 脚手架学习工程演进，保留原源码作者注释与 `server/README.md` 中的原始说明。Bundled resources retain their accompanying notices and license files.
