# Agent 命令权限

当前使用危险操作黑名单，未命中的命令和工具直接执行，不再使用查询白名单或逐条审批。

- 允许：安装软件、sudo、服务启停/重启、账号权限修改、脚本、自定义程序、管道、重定向、变量、多行命令及配置修改。
- 拦截：rm（不限于 -rf）、rmdir、unlink、shred、find -delete/-exec rm、rsync --delete、Docker/Podman rm/rmi/prune、git clean，以及 dd、mkfs、wipefs、blkdiscard 等磁盘破坏操作。检查复合命令、常见 sudo 等包装、shell -c 和命令替换中的显式删除。
- updateConfigFile 无需审批，支持任意已有配置文件的绝对路径；保留旧内容比对、符号链接检查、备份和原子替换。需要远端 sudo -n 和 Python 3。
- 保留实际账号权限、执行审计、请求/命令超时、重复失败保护。查询白名单仅用于判断是否可安全重试，不决定是否允许执行。
- 旧的待审批命令不能按新策略重放；请重新发起任务。

黑名单是常见危险写法的预检，不是 Shell 沙箱。开放任意脚本、外部程序和其它工具后，无法保证识别动态拼接、编码脚本、下载程序或工具内部的所有删除行为。远端操作系统的权限仍是最终边界。

源码：server/walissh-server-domain/src/main/java/cn/bugstack/ai/domain/ssh/service/policy/CommandPolicyService.java
启动包：ai-agent-blacklist-release.jar；start-local.ps1 会自动选择最新版本。

## 普通服务器状态查询

普通“查询服务器状态”只派发一次诊断，优先以一条有界复合命令取得负载、内存、磁盘、主要进程和失败服务；发现具体异常后才追加对应检查，不默认遍历 DNS、网关 ping、内核日志和全量系统日志。这是任务范围约束，不是工具调用次数上限。

同一终端的命令按先后顺序排队，等待时间包含在原命令超时预算内。排队超时返回 COMMAND_QUEUE_TIMEOUT，明确表示尚未发送，不消耗远程执行失败额度；保留已取得结果，报告未完成项。停止后才返回的并行成功结果不会把停止时的连续失败计数重置为零。

最新启动包：ai-agent-status-queue-release.jar。
