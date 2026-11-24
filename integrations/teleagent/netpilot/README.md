# NetPilot 网枢

NetPilot 用少量只读检查为网站访问报障提供证据。首版包含单目标系统名称解析、TCP、TLS、HTTP HEAD、Windows 网卡与默认路由摘要、代理配置迹象、Markdown/JSON 报告和复测。HTTP(S) 访问使用直接连接，目的是观察本机直连路径。代理配置仅作为补查线索。

## 在 TeleAgent 中使用

克隆仓库后，在仓库根目录生成导入包。ZIP 应包含一层 `netpilot/` 目录。

```powershell
git archive --format=zip --prefix=netpilot/ --output=netpilot.zip HEAD:integrations/teleagent/netpilot SKILL.md references scripts
```

导入 `netpilot.zip`，在新的对话里选择 NetPilot。可以说“实验室网站打不开，请检查我允许探测的站点，并给我一份报障摘要”。先确认采集环境。若脚本不能在报障电脑上执行，可在那台 Windows 电脑运行脚本，再把脱敏报告提供给 TeleAgent。

企业或学校网络的数据分享遵循现场规则。比赛环境的脚本权限、文件交付、远程接入和既有代码披露要求以 `platform-compatibility.md` 的待验证项为准。

## 在 Windows 本机运行

需要 Python 3.10+。Windows 网卡和路由摘要使用系统 PowerShell，Python 采集器只用标准库。以下地址仅示范参数，用你有权检查的实际站点替换。`--output-dir` 指向一个已存在目录。省略它时输出为 stdout JSON。

```powershell
python -B .\scripts\netpilot.py capabilities
python -B .\scripts\netpilot.py diagnose --target https://example.test --authorize --output-dir .
python -B .\scripts\netpilot.py retest --target https://example.test --input .\netpilot-原任务编号.json --authorize --output-dir .
```

每次运行使用独立任务编号。复测需要原报告，也需要再次明确目标和许可。诊断输出的 2xx 表示当前采集进程到根路径的这一项检查成功。

## 可选的 final-test 接入

NetPilot 可查询已由管理员配置的预设服务状态。正式接入条件、配置和隔离测试见 [远程查询说明](references/remote-service.md)。默认使用本机诊断流程。

## 本机演示与报障交接

从源码目录执行以下命令。演示只监听本机回环地址，自动实测 HTTP 503 与恢复后的 200，并输出前后报告、交接单和可复制给 TeleAgent 的验收输入。把验收输入粘贴到选择了 NetPilot 的新对话时，明确使用已有证据。该模式由 TeleAgent 负责解释和交接，已有报告注明采集范围。

```powershell
python -B .\demo\run_demo.py --output-dir .
python -B .\scripts\netpilot.py handoff --baseline .\首次.json --retest .\复测.json --output .\报障交接单.md
```

若已执行可选的服务器侧查询，可以给交接命令另加 `--remote .\远程证据.json`。报告会分别呈现设备侧和服务器侧的观测。

## 运行本地测试

```powershell
python -B -m unittest discover -s tests -v
```

测试用本机回环地址启动临时 HTTP 服务，分别核对真实响应、超时和复测。规则边界使用固定证据样例。技能包的入口、运行脚本和排障参考都在源码目录中。
