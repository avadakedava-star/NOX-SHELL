package cn.bugstack.ai.infrastructure.adapter.port;

import cn.bugstack.ai.domain.ssh.adapter.port.ITerminalSessionPort;
import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终端会话管理器
 * 基础设施层实现，管理 Shell 通道的创建、读写、关闭
 *
 * @author waissh dev
 */
@Slf4j
@Component
public class TerminalSessionPort implements ITerminalSessionPort {

    private final Map<String, java.util.concurrent.locks.ReentrantLock> commandLocks = new ConcurrentHashMap<>();

    /** AI 查询使用独立 exec 通道，避免提示符猜测、共享缓冲和手动输入串扰。 */
    @Override
    public cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult executeStructured(String sessionId, String command, long timeoutMs) {
        var shell = channels.get(sessionId);
        if (shell == null || !shell.isConnected())
            return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("NO_TERMINAL", false);
        var lock = commandLocks.computeIfAbsent(sessionId, ignored -> new java.util.concurrent.locks.ReentrantLock(true));
        boolean locked = false;
        try {
            // 同一终端公平排队，等待时间计入本次预算，避免子 Agent 收到 BUSY 后反复改写命令。
            long budgetNanos = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(Math.max(1, timeoutMs));
            long start = System.nanoTime();
            locked = lock.tryLock(budgetNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
            long remaining = budgetNanos - (System.nanoTime() - start);
            if (!locked || remaining <= 0)
                return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("COMMAND_QUEUE_TIMEOUT", false);
            if (!shell.isConnected())
                return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("NO_TERMINAL", false);
            return commandRunner().execute(shell.getSession(), command,
                    Math.max(1, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remaining)));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("CANCELLED", false);
        } catch (com.jcraft.jsch.JSchException error) {
            return cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult.notSent("CONNECTION_UNAVAILABLE", false);
        } finally { if (locked) lock.unlock(); }
    }

    protected SshExecCommandRunner commandRunner() { return new SshExecCommandRunner(); }

    /** Shell prompt 检测用的常见标记 */
    private static final String[] PROMPT_MARKERS = {"$ ", "# ", "% "};

    @Resource
    private SshSessionPort sshSessionService;

    /** sessionId -> Shell 通道 */
    private final Map<String, ChannelShell> channels = new ConcurrentHashMap<>();

    /** sessionId -> 输出流 */
    private final Map<String, OutputStream> outputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 输入流 */
    private final Map<String, InputStream> inputStreams = new ConcurrentHashMap<>();

    /** sessionId -> 未读输出缓冲区（前端轮询消费） */
    private final Map<String, StringBuilder> outputBuffers = new ConcurrentHashMap<>();

    /** sessionId -> AI命令执行的独立缓冲区（不受前端轮询影响） */
    private final Map<String, StringBuilder> aiCommandBuffers = new ConcurrentHashMap<>();

    /** sessionId -> 是否正在执行AI命令（为true时reader写入aiCommandBuffers，否则写入outputBuffers） */
    private final Map<String, Boolean> aiCommandMode = new ConcurrentHashMap<>();

    /** sessionId -> 读取线程是否存活 */
    private final Map<String, Boolean> readerAlive = new ConcurrentHashMap<>();

    /** sessionId -> 会话是否仍然存活（channel 未断开） */
    private final Map<String, Boolean> sessionAlive = new ConcurrentHashMap<>();

    /** connectionId -> 当前活跃的 sessionId（一个连接只允许一个终端会话） */
    private final Map<String, String> activeConnectionSession = new ConcurrentHashMap<>();

    @Override
    public String openTerminal(String connectionId, int cols, int rows) {
        // 同一 connectionId 只允许一个终端会话，先关闭旧的
        String oldSessionId = activeConnectionSession.get(connectionId);
        if (oldSessionId != null) {
            log.info("关闭旧终端会话以避免重复 connectionId={} oldSessionId={}", connectionId, oldSessionId);
            cleanup(oldSessionId);
        }

        String sessionId = UUID.randomUUID().toString().replace("-", "");

        try {
            Session session = sshSessionService.getSession(connectionId);
            if (session == null || !session.isConnected()) {
                throw new IllegalStateException("SSH会话不可用 connectionId=" + connectionId);
            }

            ChannelShell channel = (ChannelShell) session.openChannel("shell");
            channel.setPty(true);
            channel.setPtySize(cols, rows, 480, 640);

            InputStream in = channel.getInputStream();
            OutputStream out = channel.getOutputStream();

            channel.connect(5000);

            channels.put(sessionId, channel);
            inputStreams.put(sessionId, in);
            outputStreams.put(sessionId, out);
            outputBuffers.put(sessionId, new StringBuilder());
            activeConnectionSession.put(connectionId, sessionId);
            sessionAlive.put(sessionId, true);

            // 启动输出读取线程，持续读取 shell 输出到缓冲区
            startOutputReader(sessionId, in);

            // 等待 Shell 首次输出到达 + 额外等待让 MOTD 完整积累
            // 然后消费缓冲区，作为 initialOutput 返回给前端
            // 这样前端不再依赖轮询获取初始输出，彻底解决"有时显示有时不显示"的问题
            StringBuilder buffer = outputBuffers.get(sessionId);
            long waitDeadline = System.currentTimeMillis() + 3000; // 最多等 3s 等首数据
            try {
                // 阶段1：等首数据到达
                while (System.currentTimeMillis() < waitDeadline) {
                    synchronized (buffer) {
                        if (!buffer.isEmpty()) {
                            break;
                        }
                    }
                    Thread.sleep(30);
                }
                // 阶段2：额外等 200ms 让 MOTD/prompt 完整到达
                Thread.sleep(200);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }

            log.info("终端会话打开成功 sessionId={} connectionId={}", sessionId, connectionId);
            return sessionId;

        } catch (Exception e) {
            log.error("打开终端会话失败 connectionId={}", connectionId, e);
            cleanup(sessionId);
            throw new RuntimeException("打开终端失败: " + e.getMessage(), e);
        }
    }

    /** 输入重试次数 */
    private static final int WRITE_MAX_RETRIES = 2;

    /** Write with retry on I/O failure */
    @Override
    public void write(String sessionId, String command) {
        OutputStream out = outputStreams.get(sessionId);
        if (out == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        IOException lastError = null;
        for (int attempt = 1; attempt <= WRITE_MAX_RETRIES; attempt++) {
            try {
                out.write(command.getBytes(StandardCharsets.UTF_8));
                out.flush();
                return; // 成功
            } catch (IOException e) {
                lastError = e;
                log.warn("写入终端失败 (attempt={}/{}) sessionId={} reason={}",
                        attempt, WRITE_MAX_RETRIES, sessionId, e.getMessage());
                // 最后一次重试后抛出异常
                if (attempt == WRITE_MAX_RETRIES) {
                    break;
                }
                // 短暂等待后重试
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        log.error("写入终端失败 (已重试 {} 次) sessionId={}", WRITE_MAX_RETRIES, sessionId, lastError);
        throw new RuntimeException("写入终端失败: " + lastError.getMessage(), lastError);
    }

    @Override
    public String read(String sessionId) {
        StringBuilder buffer = outputBuffers.get(sessionId);
        if (buffer == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        Boolean alive = readerAlive.get(sessionId);
        if (alive != null && !alive) {
            ChannelShell channel = channels.get(sessionId);
            if (channel == null || !channel.isConnected()) {
                return "\u001b[31m\r\n[连接已断开]\u001b[0m\r\n";
            }
            InputStream in = inputStreams.get(sessionId);
            if (in != null) {
                log.info("尝试重启终端读取线程 sessionId={}", sessionId);
                startOutputReader(sessionId, in);
            }
        }

        // 非阻塞：直接返回缓冲区当前内容，不等
        // 前端轮询本身就是等待机制，不需要后端再等
        synchronized (buffer) {
            if (buffer.isEmpty()) {
                return "";
            }
            String output = buffer.toString();
            buffer.setLength(0);
            return output;
        }
    }

    /**
     * 执行命令并等待输出完成（用于 AI 工具调用）
     * <p>
     * 使用独立缓冲区，不受前端轮询影响。
     * 核心逻辑：
     * 1. 切换到AI命令模式，reader线程将输出写入独立缓冲区
     * 2. 先 drain 独立缓冲区残余数据
     * 3. 写入命令 + \n
     * 4. 用 wait/notifyAll 等待输出稳定（检测到 prompt 或超时）
     * 5. 切换回正常模式，返回清理后的输出
     */
    @Override
    public String executeCommandAndWait(String sessionId, String command, long timeoutMs) {
        StringBuilder frontBuffer = outputBuffers.get(sessionId);
        if (frontBuffer == null) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        // 校验 channel 是否仍然存活，避免往已断开的连接写命令导致静默失败
        ChannelShell channel = channels.get(sessionId);
        if (channel == null || !channel.isConnected()) {
            sessionAlive.put(sessionId, false);
            log.warn("[executeCommandAndWait] SSH通道已断开，拒绝执行 sessionId={} command={}", sessionId, command);
            throw new IllegalStateException("SSH终端连接已断开，请重新打开终端连接后重试");
        }
        Boolean alive = sessionAlive.get(sessionId);
        if (alive != null && !alive) {
            log.warn("[executeCommandAndWait] 会话已标记为失效 sessionId={} command={}", sessionId, command);
            throw new IllegalStateException("SSH终端连接已断开，请重新打开终端连接后重试");
        }

        // 创建或获取AI命令独立缓冲区
        StringBuilder aiBuffer = aiCommandBuffers.computeIfAbsent(sessionId, k -> new StringBuilder());

        // 1. 切换到AI命令模式
        aiCommandMode.put(sessionId, true);
        synchronized (aiBuffer) {
            aiBuffer.setLength(0); // drain 残余数据
        }
        // 也 drain 前端缓冲区，避免混淆
        synchronized (frontBuffer) {
            frontBuffer.setLength(0);
        }

        try {
            // 2. 写入命令 + 换行符
            String fullCommand = command.endsWith("\n") ? command : command + "\n";
            try {
                OutputStream out = outputStreams.get(sessionId);
                if (out == null) {
                    throw new IllegalStateException("终端输出流不可用 sessionId=" + sessionId);
                }
                out.write(fullCommand.getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException e) {
                sessionAlive.put(sessionId, false);
                log.error("[executeCommandAndWait] 写入终端失败，标记会话失效 sessionId={} reason={}", sessionId, e.getMessage());
                throw new RuntimeException("SSH终端连接已断开: " + e.getMessage(), e);
            }
            log.info("[executeCommandAndWait] 命令已写入 sessionId={} command={}", sessionId, command);

            // 3. 等待输出稳定（使用 wait/notifyAll 机制）
            long deadline = System.currentTimeMillis() + timeoutMs;
            int stableCount = 0;
            final int STABLE_THRESHOLD = 3; // 连续 3 次无新数据认为输出完成
            final long POLL_INTERVAL = 100; // 轮询间隔 ms

            while (System.currentTimeMillis() < deadline) {
                // reader 线程已死且缓冲区无数据，说明连接已断开，快速失败
                Boolean readerRunning = readerAlive.get(sessionId);
                if (Boolean.FALSE.equals(readerRunning) && aiBuffer.length() == 0) {
                    sessionAlive.put(sessionId, false);
                    log.warn("[executeCommandAndWait] reader线程已退出且无输出，判定连接断开 sessionId={}", sessionId);
                    throw new IllegalStateException("SSH终端连接已断开，请重新打开终端连接后重试");
                }

                synchronized (aiBuffer) {
                    // 等待数据到达或超时
                    long waitMs = Math.min(POLL_INTERVAL, deadline - System.currentTimeMillis());
                    if (waitMs > 0 && aiBuffer.length() == 0) {
                        try {
                            aiBuffer.wait(waitMs);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }

                    if (aiBuffer.length() > 0) {
                        stableCount = 0; // 有新数据，重置稳定计数
                    } else {
                        stableCount++;
                    }
                }

                // 检查是否输出稳定
                if (stableCount >= STABLE_THRESHOLD) {
                    String output;
                    synchronized (aiBuffer) {
                        output = aiBuffer.toString();
                    }
                    if (output.length() > 0 && containsPrompt(output)) {
                        log.info("[executeCommandAndWait] 输出稳定(检测到prompt) sessionId={} outputLength={}",
                                sessionId, output.length());
                        break;
                    }
                    // 没有 prompt 但稳定了很久，也认为完成
                    if (stableCount >= STABLE_THRESHOLD * 3) {
                        log.info("[executeCommandAndWait] 输出稳定(无prompt超时) sessionId={}", sessionId);
                        break;
                    }
                }
            }

            // 4. 收集最终结果
            String result;
            synchronized (aiBuffer) {
                result = aiBuffer.toString();
                aiBuffer.setLength(0);
            }

            // 5. 清理输出
            result = cleanCommandOutput(result, command);

            log.info("[executeCommandAndWait] 完成 sessionId={} resultLength={} resultPreview={}",
                    sessionId, result.length(),
                    result.length() > 200 ? result.substring(0, 200) + "..." : result);

            return result;

        } finally {
            // 6. 切换回正常模式
            aiCommandMode.put(sessionId, false);
        }
    }

    /**
     * 检查输出是否包含 Shell prompt 标记
     */
    private boolean containsPrompt(String output) {
        if (output == null || output.isEmpty()) return false;
        // 检查最后 50 个字符中是否包含 prompt 标记
        String tail = output.length() > 50 ? output.substring(output.length() - 50) : output;
        for (String marker : PROMPT_MARKERS) {
            if (tail.contains(marker)) return true;
        }
        return false;
    }

    /**
     * 清理命令输出：
     * - 去除第一行命令回显
     * - 去除末尾 prompt 行
     * - 去除 ANSI 控制序列
     */
    private String cleanCommandOutput(String output, String command) {
        if (output == null || output.isEmpty()) return "";

        // 去除 ANSI 转义序列
        String cleaned = output.replaceAll("\u001b\\[[0-9;]*[a-zA-Z]", "");
        // 去除回车符
        cleaned = cleaned.replace("\r", "");

        // 按行分割
        String[] lines = cleaned.split("\n");
        java.util.List<String> resultLines = new java.util.ArrayList<>();

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmedLine = line.trim();

            // 跳过空行
            if (trimmedLine.isEmpty()) continue;

            // 跳过第一行如果是命令回显
            if (resultLines.isEmpty() && isCommandEcho(trimmedLine, command)) {
                continue;
            }

            // 跳过末尾 prompt 行
            if (i == lines.length - 1 && isPromptLine(trimmedLine)) {
                continue;
            }

            resultLines.add(line);
        }

        return String.join("\n", resultLines).trim();
    }

    /**
     * 判断是否为命令回显行
     */
    private boolean isCommandEcho(String line, String command) {
        String cmd = command.trim();
        // 精确匹配或前缀匹配（Shell 有时会加 prompt 前缀）
        if (line.equals(cmd)) return true;
        // 处理带 prompt 前缀的情况，如 "ubuntu@host:~$ cat /etc/os-release"
        if (line.endsWith(cmd)) return true;
        // 命令很短时只做精确匹配，避免误杀
        if (cmd.length() > 10 && line.contains(cmd)) return true;
        return false;
    }

    /**
     * 判断是否为 prompt 行（如 "ubuntu@VM-0-7-ubuntu:~$"）
     */
    private boolean isPromptLine(String line) {
        for (String marker : PROMPT_MARKERS) {
            if (line.endsWith(marker.trim())) return true;
        }
        // 匹配 user@host:dir$ 格式
        if (line.matches(".*@.*:[^$]*\\$\\s*$")) return true;
        if (line.matches(".*@.*:[^#]*#\\s*$")) return true;
        return false;
    }

    @Override
    public void resize(String sessionId, int cols, int rows) {
        ChannelShell channel = channels.get(sessionId);
        if (channel == null || !channel.isConnected()) {
            throw new IllegalArgumentException("终端会话不存在或已关闭 sessionId=" + sessionId);
        }

        try {
            channel.setPtySize(cols, rows, 480, 640);
            log.debug("终端大小已调整 sessionId={} {}x{}", sessionId, cols, rows);
        } catch (Exception e) {
            log.error("调整终端大小失败 sessionId={}", sessionId, e);
            throw new RuntimeException("调整终端大小失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void closeSession(String sessionId) {
        log.info("关闭终端会话 sessionId={}", sessionId);
        cleanup(sessionId);
    }

    @Override
    public boolean sessionExists(String sessionId) {
        ChannelShell channel = channels.get(sessionId);
        if (channel == null || !channel.isConnected()) {
            return false;
        }
        Boolean alive = sessionAlive.get(sessionId);
        return alive == null || alive;
    }

    // ========== 内部方法 ==========

    /**
     * 启动输出读取线程
     * SocketTimeoutException 时继续循环（不是真正的断连），
     * 只有 EOF（-1）或真正的 IOException 才退出
     */
    private void startOutputReader(String sessionId, InputStream in) {
        readerAlive.put(sessionId, true);
        Thread reader = new Thread(() -> {
            byte[] buf = new byte[4096];
            try {
                int len;
                while ((len = in.read(buf)) != -1) {
                    String text = new String(buf, 0, len, StandardCharsets.UTF_8);
                    // 根据模式决定写入哪个缓冲区
                    Boolean aiMode = aiCommandMode.get(sessionId);
                    if (Boolean.TRUE.equals(aiMode)) {
                        // AI命令模式：写入独立缓冲区
                        StringBuilder aiBuffer = aiCommandBuffers.get(sessionId);
                        if (aiBuffer != null) {
                            synchronized (aiBuffer) {
                                aiBuffer.append(text);
                                aiBuffer.notifyAll(); // 通知等待的 executeCommandAndWait
                            }
                        }
                        // 同时也写入前端缓冲区，让前端能看到命令回显
                        StringBuilder frontBuffer = outputBuffers.get(sessionId);
                        if (frontBuffer != null) {
                            synchronized (frontBuffer) {
                                frontBuffer.append(text);
                            }
                        }
                    } else {
                        // 正常模式：写入前端缓冲区
                        StringBuilder buffer = outputBuffers.get(sessionId);
                        if (buffer != null) {
                            synchronized (buffer) {
                                buffer.append(text);
                            }
                        }
                    }
                }
                // in.read() 返回 -1，说明 shell channel EOF，连接已断开
                sessionAlive.put(sessionId, false);
                log.warn("终端 Shell Channel EOF，标记会话失效 sessionId={}", sessionId);
            } catch (java.net.SocketTimeoutException e) {
                // SocketTimeout 不是断连，重试即可——但实际不应到这里了（setTimeout(0)）
                // 保留作为防御性处理
                log.debug("终端读取超时（非断连），继续读取 sessionId={}", sessionId);
                InputStream inRef = inputStreams.get(sessionId);
                if (inRef != null) {
                    startOutputReader(sessionId, inRef);
                }
                return; // 当前线程退出，新线程已接替
            } catch (IOException e) {
                ChannelShell ch = channels.get(sessionId);
                // channel 还活着说明不是真正的断连，可能是临时 I/O 问题，尝试重启
                if (ch != null && ch.isConnected()) {
                    log.warn("终端读取I/O异常但channel仍连接，重启reader sessionId={} reason={}", sessionId, e.getMessage());
                    InputStream inRef = inputStreams.get(sessionId);
                    if (inRef != null) {
                        startOutputReader(sessionId, inRef);
                    }
                    return;
                }
                // channel 已断开，标记会话失效
                sessionAlive.put(sessionId, false);
                log.warn("终端输出读取异常(channel已断开)，标记会话失效 sessionId={} reason={}", sessionId, e.getMessage());
            } finally {
                readerAlive.put(sessionId, false);

                // 诊断：为什么线程退出了？
                ChannelShell ch = channels.get(sessionId);
                boolean channelConnected = ch != null && ch.isConnected();
                boolean channelClosed = ch != null && ch.isClosed();
                // channel 已断开时标记会话失效，让 executeCommandAndWait 快速失败
                if (!channelConnected) {
                    sessionAlive.put(sessionId, false);
                }
                log.warn("终端输出读取线程退出 sessionId={} channelConnected={} channelClosed={} sessionAlive={}",
                        sessionId, channelConnected, channelClosed, sessionAlive.get(sessionId));

                // 通知等待中的 read() 方法：线程已退出，不再有数据
                StringBuilder buffer = outputBuffers.get(sessionId);
                if (buffer != null) {
                    synchronized (buffer) {
                        buffer.notifyAll();
                    }
                }
                // 通知等待中的 executeCommandAndWait：线程已退出，让它能检测到断连
                StringBuilder aiBuffer = aiCommandBuffers.get(sessionId);
                if (aiBuffer != null) {
                    synchronized (aiBuffer) {
                        aiBuffer.notifyAll();
                    }
                }
            }
        }, "terminal-reader-" + sessionId);
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * 清理资源
     */
    private void cleanup(String sessionId) {
        commandLocks.remove(sessionId);
        // 清理 connectionId -> sessionId 映射
        activeConnectionSession.entrySet().removeIf(e -> sessionId.equals(e.getValue()));

        try {
            OutputStream out = outputStreams.remove(sessionId);
            if (out != null) out.close();
        } catch (IOException ignored) {}

        try {
            InputStream in = inputStreams.remove(sessionId);
            if (in != null) in.close();
        } catch (IOException ignored) {}

        ChannelShell channel = channels.remove(sessionId);
        if (channel != null && channel.isConnected()) {
            channel.disconnect();
        }

        outputBuffers.remove(sessionId);
        readerAlive.remove(sessionId);
        sessionAlive.remove(sessionId);
        aiCommandBuffers.remove(sessionId);
        aiCommandMode.remove(sessionId);
    }

}
