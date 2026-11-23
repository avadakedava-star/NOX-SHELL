# 聊天模式

在前端「设置 → 通用 → 聊天模式」选择并保存，从下一条消息起生效。选择保存在当前浏览器中，未设置时使用标准模式。运行中的请求不会切换路线。

- 标准模式（`standard`）：原有 `ChatService → ADK Runner` 路线，使用运行期间的近期 Session 对话。
- 增强模式（`enhanced`）：执行 `RootNode → AiCallNode → ToolCallNode（有工具记录时）→ LoopDecisionNode → UserFeedbackNode`，使用 case 中的数据库历史加载、意图识别、历史裁剪、动态上下文注入和记忆记录。

两种模式均由 ADK 负责模型与工具交互，不额外套一层反复执行工具的循环。增强模式的原始事件接入同一个 HTTP SSE 生命周期，复用工具审计、子任务事件、命令审批、取消、超时与错误收尾。

API 请求通过 `executionMode` 指定模式，缺省或空值为 `standard`；非法值返回错误，不执行模型。`/api/v1/chat_stream` 和 `/api/v1/chat` 均识别该字段。

历史恢复要求数据库可用，并且该会话已有保存的消息。标准模式未保存的对话不会因切换而自动补写；前端展示的旧聊天记录也不等于模型已获得历史上下文。

本地启动脚本支持 `ai-agent-chat-mode-release.jar`。定向验证：前端 `npm run build`、`npm run test:execution-state`、`npm run test:chat-mode`；后端 `ChatExecutionModeTest`、`CaseEventBridgeTest`、`AgentStreamLifecycleTest`、`ToolExecutionAuditTest`。
