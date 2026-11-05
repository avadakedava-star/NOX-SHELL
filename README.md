# WaliSSH Agent

SSH 智能运维助手，包含 React / TypeScript 前端与 Java 17 / Spring Boot 后端。

## 功能

- 流式对话、真实工具执行审计和明确的请求结束事件。
- SSH 命令执行、危险操作黑名单、同一终端排队。
- 持久化后台长任务、容器部署、就绪检查、任务恢复与取消。
- 任务进度卡、异常中文说明，以及前端连接状态与后台启动改进。

## 目录

- `client/`：前端及可选 Tauri 桌面项目。
- `server/`：后端 Maven 多模块工程。
- `server/docs/dev-ops/mysql/sql/walissh.sql`：数据库表结构，不包含原环境数据。
- `长任务执行规则.md`、`流式请求收尾规则.md`：执行规则和代码入口。

## 本地运行

需要 JDK 17、Maven、Node.js 以及 MySQL。先创建 `walissh` 数据库并导入上述 SQL。

在 PowerShell 中设置环境变量（`.env.example` 只是示例，不会自动加载）：

```powershell
$env:DEEPSEEK_API_KEY = "你自己的模型密钥"
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "你自己的数据库密码"
$env:WALISSH_SECRET_KEY = "你自己生成并妥善保存的随机32位密钥"
```

默认数据库地址为 `127.0.0.1:3307/walissh`，可用 `DB_URL` 覆盖。

```powershell
cd client
npm ci
npm run build
cd ../server
mvn -s ../maven-settings.xml -DskipTests package
cd ..
./start-local.ps1
```

前端地址为 `http://127.0.0.1:5173/`，后端端口为 `8091`。`maven-settings.xml` 与启动脚本默认使用本机 `7897` 代理，按实际环境调整。后台前端使用 Windows WMI 独立启动；也可在 `client` 中手动运行 `npm run dev`。

模型地址和名称位于 `server/walissh-server-app/src/main/resources/agent/ssh-agent.yml`。可选手工集成测试需要自行配置模型、SSH 和数据库环境。

## 公开版本说明

本仓库是 `final-test` 的脱敏源码副本：不包含运行日志、数据库备份或历史记录、真实密钥、SSH 凭据、依赖目录及构建产物。原有固定密钥已改为必须通过环境变量配置。

当前保留本地固定用户模式，完整的生产身份认证与资源授权尚未实现。

## 来源与致谢

项目基于小傅哥 / bugstack.cn 的 WaliSSH 与 DDD 脚手架学习工程演进，保留原源码作者注释与 `server/README.md` 中的原始说明。Bundled resources retain their accompanying notices and license files.
