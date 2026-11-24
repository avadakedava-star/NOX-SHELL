---
name: final-test-bridge
description: 当用户要求 TeleAgent 检查服务器上的 final-test 是否在线、查看 Agent 列表，或明确让 final-test 回答普通文字问题时使用。通过服务器的 HTTP 接口查询 Agent 或发起一次独立的文字对话。
---

# 连接服务器上的 final-test

调用技能目录里的 `scripts/final_test.py`。在命令里用该脚本的绝对路径并加引号，方便从任意工作目录执行。使用 `python`，或使用 TeleAgent 已有的 Python 3。默认连接本机的 `127.0.0.1:8091`。如需连接已获授权的服务，在脚本运行环境中设置 `FINAL_TEST_HOST`，值为主机名或 IP，可附加端口。

- **检查连通性或查 Agent**：运行 `python "<技能目录>/scripts/final_test.py" status`。成功时按 `agents` 中返回的名称和 ID 报告结果。只有返回 `ok: true` 才说明 final-test 的 Agent 列表接口可用。
- **请 final-test 回答一句话**：把用户明确要转交的文字写到当前工作空间的临时 UTF-8 文件。运行 `python "<技能目录>/scripts/final_test.py" chat --message-file "<临时文件的路径>"`，然后转述返回的 `content`。本操作每次开启独立会话，使用 `standard` 和 `approval`，不附带 SSH 终端。只转交当前问题，排除凭据、文件内容与未授权的聊天历史。
- 遇到 `ok: false`，按输出的 `error` 告知用户。如果是连接失败，请确认当前网络可以访问服务器的 HTTP 端口。不要把 TeleAgent 自己生成的内容当成 final-test 的回复，也不要因聊天超时自动重复发送。

例如，「用 final-test-bridge 检查 final-test 是否在线」用于检查连通性。「让 final-test 用一句话说明什么是 SSH」用于普通文字问答。
