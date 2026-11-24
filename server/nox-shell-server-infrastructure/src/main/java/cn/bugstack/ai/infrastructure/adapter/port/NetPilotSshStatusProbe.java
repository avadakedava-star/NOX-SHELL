package cn.bugstack.ai.infrastructure.adapter.port;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import cn.bugstack.ai.domain.ssh.service.netpilot.NetPilotStatusProbe;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "nox-shell.netpilot", name = "enabled", havingValue = "true")
public final class NetPilotSshStatusProbe implements NetPilotStatusProbe {
    private static final int TIMEOUT_MS = 3500;

    private final String host;
    private final int port;
    private final String user;
    private final String knownHosts;
    private final String privateKey;
    private final String command;
    private final SshExecCommandRunner runner = new SshExecCommandRunner();

    public NetPilotSshStatusProbe(
            @Value("${nox-shell.netpilot.ssh-host}") String host,
            @Value("${nox-shell.netpilot.ssh-port:22}") int port,
            @Value("${nox-shell.netpilot.ssh-user}") String user,
            @Value("${nox-shell.netpilot.ssh-known-hosts}") String knownHosts,
            @Value("${nox-shell.netpilot.ssh-private-key}") String privateKey,
            @Value("${nox-shell.netpilot.systemd-unit}") String unit) {
        if (host.isBlank() || port < 1 || port > 65535 || user.isBlank()
                || !unit.matches("[a-zA-Z0-9][a-zA-Z0-9_.@-]{0,100}\\.service")) {
            throw new IllegalArgumentException("Invalid NetPilot SSH target or systemd unit");
        }
        this.host = host;
        this.port = port;
        this.user = user;
        this.knownHosts = requireReadableFile(knownHosts);
        this.privateKey = requireReadableFile(privateKey);
        this.command = "systemctl --no-pager show --property=LoadState --property=ActiveState -- " + unit;
    }

    @Override
    public CommandExecutionResult readServiceStatus() {
        Session session = null;
        try {
            JSch client = new JSch();
            client.setKnownHosts(knownHosts);
            client.addIdentity(privateKey);
            session = client.getSession(user, host, port);
            session.setConfig("StrictHostKeyChecking", "yes");
            session.setConfig("PreferredAuthentications", "publickey");
            session.connect(TIMEOUT_MS);
            return runner.execute(session, command, TIMEOUT_MS);
        } catch (JSchException exception) {
            return CommandExecutionResult.notSent("SSH_CONNECTION_UNAVAILABLE", false);
        } finally {
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private static String requireReadableFile(String value) {
        Path path = Path.of(value);
        if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IllegalArgumentException("NetPilot SSH files must be readable absolute paths");
        }
        return path.toString();
    }
}
