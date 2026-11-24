# final-test 远程只读查询

NetPilot 在本机采集结果仍不足、且任务需要服务器侧证据时，可以查询一个预先登记的 Linux systemd 服务。结果的观测位置是远程设备。服务运行状态可以帮助缩小范围，仍需用报障端的复测确认访问情况。

## final-test 配置

后端默认不注册此接口。本地测试或获得部署许可后，在使用的 Spring Boot 配置中添加以下独立配置。服务标识用于查询，实际 SSH 地址和 unit 由后台部署者绑定。

```yaml
nox-shell:
  netpilot:
    enabled: true
    token: ${NETPILOT_SERVICE_TOKEN}
    device-id: lab
    service-id: web
    systemd-unit: fixture.service
    ssh-host: your-authorized-linux-host
    ssh-port: 22
    ssh-user: netpilot-reader
    ssh-known-hosts: /absolute/path/to/known_hosts
    ssh-private-key: /absolute/path/to/ssh-key.pem
```

- 令牌使用随机生成的 32–128 位 URL 安全字符串，在服务端和受控运行环境中配置。技能包、对话和命令参数都无需写入令牌。
- SSH 使用专用低权限账号和独立私钥。通过可信渠道核对远程主机公钥，再写入 `known_hosts`。本地隔离实测使用 JSch 支持的 ECDSA PEM 密钥格式。正式环境需确认 SSH 算法和密钥格式兼容。
- API 为 `GET /api/v1/netpilot/devices/{deviceId}/services/{serviceId}`。服务器验证 Bearer 令牌及预设标识，每次只允许一个并发查询。SSH 只执行固定的 `systemctl show`。响应与审计日志使用证据编号、状态和已登记的资源标识。
- 对其他机器提供接口时，通过受控 HTTPS 入口接入。HTTP 客户端只允许数字形式的本机回环地址用于开发验证。

## NetPilot 调用

向 NetPilot 所在的受控进程注入 `NETPILOT_REMOTE_URL` 和 `NETPILOT_REMOTE_TOKEN`。URL 是 final-test 的 HTTPS 站点地址。本机联调可以使用 `http://127.0.0.1:<端口>`。

```powershell
python -B .\scripts\netpilot.py remote-service --device lab --service web --authorize --output .\远程证据.json
```

只有响应中的 `evidence.status` 为 `observed` 才有可用服务状态。`unknown` 或 `unavailable` 表示服务状态尚未确认。把证据的来源和编号与本机探测的证据分别写明。接口不可用时，可以照常交付本机诊断结果。

## 复现隔离联调

在开发环境准备 Maven、本项目的 Maven 本地缓存，以及测试用 Python 环境里的 Paramiko。由项目根目录运行：

```powershell
python -B .\integrations\teleagent\netpilot\tests\run_remote_fixture.py
```

脚本使用随机的本机回环端口和临时 SSH 密钥。Spring 测试实例接收真实 HTTP，JSch 进行真实 SSH 公钥认证及命令调用。测试 SSH 服务返回固定的 systemd 响应，以便同时核对正常主机密钥和错误主机密钥。这个用例不依赖实验服务器或生产服务。
