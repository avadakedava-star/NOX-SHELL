param([string]$ServerJarName, [switch]$NoBrowser)

$ErrorActionPreference = 'Stop'
$taskRoot = $PSScriptRoot
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { (Get-Command java.exe -ErrorAction Stop).Source }
if ([string]::IsNullOrWhiteSpace($ServerJarName)) {
    $taskCandidates = @('ai-agent-scaffold-lite-app.jar', 'ai-agent-tool-audit.jar', 'ai-agent-execution-policy.jar', 'ai-agent-execution-policy-relaxed.jar', 'ai-agent-execution-policy-systemctl.jar', 'ai-agent-failure-limits.jar', 'ai-agent-command-approval.jar', 'ai-agent-approval-release.jar', 'ai-agent-blacklist-release.jar', 'ai-agent-status-queue-release.jar', 'ai-agent-run-finish-release.jar', 'ai-agent-human-summary-release.jar', 'ai-agent-long-task-release.jar', 'ai-agent-chat-mode-release.jar', 'ai-agent-terminal-binding-release.jar', 'ai-agent-progress-policy-release.jar', 'ai-agent-progress-policy-intent-fix.jar', 'ai-agent-permission-mode-release.jar', 'ai-agent-approval-refinement-release.jar', 'ai-agent-readonly-nginx-release.jar', 'ai-agent-explicit-approval-release.jar') |
        ForEach-Object { Get-Item -LiteralPath (Join-Path $taskRoot "server\nox-shell-server-app\target\$_") -ErrorAction SilentlyContinue }
    $ServerJarName = ($taskCandidates | Sort-Object LastWriteTime -Descending | Select-Object -First 1).Name
}
if ([string]::IsNullOrWhiteSpace($ServerJarName)) { throw '请先按联调说明打包后端。' }
$taskJar = Join-Path $taskRoot "server\nox-shell-server-app\target\$ServerJarName"
if (!(Test-Path -LiteralPath $taskJava)) { throw "请将 JAVA_HOME 指向有效的 JDK 安装目录。当前 Java 路径：$taskJava" }
if (!(Test-Path -LiteralPath $taskJar)) { throw '请先按联调说明打包后端。' }
if (!(Test-Path -LiteralPath (Join-Path $taskRoot 'client\node_modules\vite\bin\vite.js'))) { throw '请先在 client 目录运行 npm ci。' }
New-Item -ItemType Directory -Path (Join-Path $taskRoot 'java-tmp') -Force | Out-Null
if (!(Get-NetTCPConnection -State Listen -LocalPort 8091 -ErrorAction SilentlyContinue)) {
    $taskBackend = Start-Process -FilePath $taskJava -ArgumentList @(
        "`"-Djdk.net.unixdomain.tmpdir=$taskRoot\java-tmp`"",
        '-Dhttps.proxyHost=127.0.0.1', '-Dhttps.proxyPort=7897',
        '-Dhttp.proxyHost=127.0.0.1', '-Dhttp.proxyPort=7897',
        '-Dhttp.nonProxyHosts=localhost|127.*|[::1]',
        '-jar', "`"$taskJar`"",
        '--spring.profiles.active=dev'
    ) -WorkingDirectory (Join-Path $taskRoot 'server') -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $taskRoot 'server-run.log') `
        -RedirectStandardError (Join-Path $taskRoot 'server-run.err.log') -PassThru
    $taskBackend.Id | Set-Content (Join-Path $taskRoot 'server.pid')
    Write-Output "后端已发起启动，PID: $($taskBackend.Id)，请查看 server-run.log 确认完成。"
} else { Write-Output '8091 已有服务，本脚本未重复启动后端。' }
if (!(Get-NetTCPConnection -State Listen -LocalPort 5173 -ErrorAction SilentlyContinue)) {
    $taskNode = (Get-Command node.exe).Source
    # 由 Windows 任务计划服务托管，生命周期独立于启动命令。
    $taskClientRoot = Join-Path $taskRoot 'client'
    $taskPython = (Get-Command pythonw.exe -ErrorAction Stop).Source
    $taskFrontendScript = Join-Path $taskClientRoot 'scripts\supervise-dev.py'
    $taskFrontendName = 'NOX-SHELL-final-test-frontend'
    $taskAction = New-ScheduledTaskAction `
        -Execute $taskPython `
        -Argument ('"' + $taskFrontendScript + '" "' + $taskNode + '"') `
        -WorkingDirectory $taskClientRoot
    $taskSettings = New-ScheduledTaskSettingsSet -ExecutionTimeLimit ([TimeSpan]::Zero) `
        -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
        -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
    $taskPrincipal = New-ScheduledTaskPrincipal `
        -UserId ([System.Security.Principal.WindowsIdentity]::GetCurrent().Name) `
        -LogonType Interactive -RunLevel Limited
    Register-ScheduledTask -TaskName $taskFrontendName -Action $taskAction `
        -Settings $taskSettings -Principal $taskPrincipal -Force | Out-Null
    Start-ScheduledTask -TaskName $taskFrontendName
    Write-Output "前端已交由 Windows 任务计划服务启动：$taskFrontendName。"
} else { Write-Output '5173 已有服务，本脚本未重复启动前端。' }
Write-Output '页面地址：http://127.0.0.1:5173/'

Write-Output '正在等待前后端准备好（最多 120 秒）……'
$taskDeadline = (Get-Date).AddSeconds(120)
$taskFrontendReady = $false
$taskBackendReady = $false
function Get-LocalResponse([string]$Url) {
    $taskRequest = [System.Net.HttpWebRequest]::Create($Url)
    $taskRequest.Proxy = $null
    $taskRequest.Timeout = 3000
    $taskRequest.ReadWriteTimeout = 3000
    $taskResponse = $taskRequest.GetResponse()
    try {
        $taskReader = New-Object System.IO.StreamReader($taskResponse.GetResponseStream(), [System.Text.Encoding]::UTF8)
        try { return $taskReader.ReadToEnd() } finally { $taskReader.Dispose() }
    } finally { $taskResponse.Dispose() }
}
    do {
        if (!$taskFrontendReady) {
            try {
                $taskPage = Get-LocalResponse 'http://127.0.0.1:5173/'
                $taskFrontendReady = $taskPage -match '/@vite/client'
            } catch { }
        }
        if (!$taskBackendReady) {
            try {
                $taskReply = Get-LocalResponse 'http://127.0.0.1:8091/api/v1/query_ai_agent_config_list' | ConvertFrom-Json
                $taskBackendReady = $taskReply.code -eq '0000'
            } catch { }
        }
        if ($taskFrontendReady -and $taskBackendReady) { break }
        if ($taskBackend -and $taskBackend.HasExited) {
            throw '后端进程已退出，请查看 server-run.log 和 server-run.err.log。'
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $taskDeadline)
if (!$taskFrontendReady -or !$taskBackendReady) {
    throw "启动未完成：前端就绪=$taskFrontendReady，后端就绪=$taskBackendReady。请查看当前目录的 client-run.err.log、client-bootstrap.err.log 和 server-run.log。"
}
Write-Output '启动成功！前后端已准备好，关闭本窗口后仍会在后台运行。'
if (!$NoBrowser) { Start-Process 'http://127.0.0.1:5173/' }
