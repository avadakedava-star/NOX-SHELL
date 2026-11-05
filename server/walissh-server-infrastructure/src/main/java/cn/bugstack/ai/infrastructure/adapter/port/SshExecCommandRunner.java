package cn.bugstack.ai.infrastructure.adapter.port;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.Session;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** connect 可能已经发出 exec 请求，因此从 connect 之前起将后续异常视为结果未知。 */
public class SshExecCommandRunner {
    static final int OUTPUT_LIMIT = 65536;
    protected ChannelExec open(Session session) throws Exception { return (ChannelExec) session.openChannel("exec"); }

    public CommandExecutionResult execute(Session session, String command, long timeoutMs) {
        ChannelExec channel = null;
        boolean dispatched = false;
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        long deadline = System.nanoTime() + Math.max(1, timeoutMs) * 1000000L;
        try {
            if (session == null || !session.isConnected()) return CommandExecutionResult.notSent("CONNECTION_UNAVAILABLE", false);
            channel = open(session);
            channel.setCommand(command);
            channel.setInputStream(null);
            InputStream out = channel.getInputStream(), err = channel.getExtInputStream();
            dispatched = true;
            channel.connect((int) Math.min(5000, Math.max(1, timeoutMs)));
            while (true) {
                if (!drain(out, stdout, stderr.size()) || !drain(err, stderr, stdout.size()))
                    return CommandExecutionResult.unknown(text(stdout), text(stderr), "OUTPUT_LIMIT");
                if (channel.isClosed()) {
                    if (out.available() > 0 || err.available() > 0) continue;
                    int code = channel.getExitStatus();
                    return code < 0 ? CommandExecutionResult.unknown(text(stdout), text(stderr), "EXIT_STATUS_MISSING")
                            : CommandExecutionResult.completed(code, text(stdout), text(stderr));
                }
                if (!session.isConnected()) return CommandExecutionResult.unknown(text(stdout), text(stderr), "CONNECTION_LOST");
                if (System.nanoTime() >= deadline) return CommandExecutionResult.unknown(text(stdout), text(stderr), "COMMAND_TIMEOUT");
                Thread.sleep(20);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return dispatched ? CommandExecutionResult.unknown(text(stdout), text(stderr), "CANCELLED") : CommandExecutionResult.notSent("CANCELLED", false);
        } catch (Exception error) {
            return dispatched ? CommandExecutionResult.unknown(text(stdout), text(stderr), "TRANSPORT_AFTER_DISPATCH")
                    : CommandExecutionResult.notSent("TRANSPORT_BEFORE_DISPATCH", true);
        } finally { if (channel != null) channel.disconnect(); }
    }

    private boolean drain(InputStream input, ByteArrayOutputStream output, int otherSize) throws Exception {
        byte[] bytes = new byte[4096];
        while (input.available() > 0) {
            int capacity = OUTPUT_LIMIT - output.size() - otherSize;
            if (capacity <= 0) return false;
            int count = input.read(bytes, 0, Math.min(capacity, Math.min(bytes.length, input.available())));
            if (count < 0) break;
            output.write(bytes, 0, count);
        }
        return true;
    }
    private String text(ByteArrayOutputStream output) { return output.toString(StandardCharsets.UTF_8); }
}
