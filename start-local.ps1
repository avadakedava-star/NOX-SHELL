param([string]$ServerJarName)

$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
if ([string]::IsNullOrWhiteSpace($ServerJarName)) {
    $taskCandidates = @('ai-agent-scaffold-lite-app.jar', 'ai-agent-tool-audit.jar', 'ai-agent-execution-policy.jar', 'ai-agent-execution-policy-relaxed.jar', 'ai-agent-execution-policy-systemctl.jar', 'ai-agent-failure-limits.jar', 'ai-agent-command-approval.jar', 'ai-agent-approval-release.jar', 'ai-agent-blacklist-release.jar', 'ai-agent-status-queue-release.jar', 'ai-agent-run-finish-release.jar', 'ai-agent-human-summary-release.jar', 'ai-agent-long-task-release.jar') |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $taskRoot "server\walissh-server-app\target\$_") -ErrorAction SilentlyContinue }
    $ServerJarName = ($taskCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
}
if ([string]::IsNullOrWhiteSpace($ServerJarName)) { throw '请先按联调说明打包后端。' }
$taskJar = Join-Path $taskRoot "server\walissh-server-app\target\$ServerJarName"
if (!(Test-Path -LiteralPath $taskJava)) { throw "请修改脚本中的 JDK 路径：$taskJava" }
if (!(Test-Path -LiteralPath $taskJar)) { throw '请先按联调说明打包后端。' }
if (!(Test-Path -LiteralPath (Join-Path $taskRoot 'client\node_modules\vite\bin\vite.js'))) { throw '请先在 client 目录运行 npm ci。' }
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'java-tmp') -Force | Out-Null
if (!(Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue)) {
    $taskBackend = Start-Process -FilePath $taskJava -ArgumentList @(
        "-Djdk.net.unixdomain.tmpdir=$taskRoot\java-tmp",
        '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=7897',
        '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=7897',
        '-Dhttp.nonProxyHosts=localhost|127.*|[::1]',
        '-jar', $taskJar,
        '--spring.profiles.active=dev'
    ) -WorkingDirectory (Join-Path $taskRoot 'server') -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $taskRoot 'server-run.log') `
        -RedirectStandardError (Join-Path $taskRoot 'server-run.err.log') -PassThru
    $taskBackend.Id | Set-Content (Join-Path $taskRoot 'server.pid')
    Write-Output "后端已发起启动，PID: $($taskBackend.Id)，请查看 server-run.log 确认完成。"
} else { Write-Output '8091 已有服务，本脚本未重复启动后端。' }
if (!(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)) {
    $taskNode = (Get-Command node.exe).Source
    # 由 Windows WMI 服务创建独立进程，避免随调用脚本/工具的进程组被回收。
    $taskClientRoot = Join-Path $taskRoot 'client'
    $taskFrontendScript = Join-Path $taskClientRoot 'scripts\start-background-dev.mjs'
    $taskStartup = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }
    $taskFrontend = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{
        CommandLine = '"' + $taskNode + '" "' + $taskFrontendScript + '"'
        CurrentDirectory = $taskClientRoot
        ProcessStartupInformation = $taskStartup
    }
    if ($taskFrontend.ReturnValue -ne 0) { throw "Windows 独立启动前端失败，错误码：$($taskFrontend.ReturnValue)" }
    $taskFrontend.ProcessId | Set-Content (Join-Path $taskRoot 'client.pid')
    Write-Output "前端已独立启动，PID: $($taskFrontend.ProcessId)。"
} else { Write-Output '5173 已有服务，本脚本未重复启动前端。' }
Write-Output '页面地址：http://127.0.0.1:5173/'
