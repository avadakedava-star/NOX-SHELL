package cn.bugstack.ai.domain.ssh.service.policy;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;
public class CommandPolicyServiceTest {
    private final CommandPolicyService policy = new CommandPolicyService();
    @Test public void nonBlacklistedCommandsKeepOriginalShellSemantics() {
        for (String command : List.of("systemctl status docker --no-pager -l", "sudo -n systemctl restart nginx", "apt-get install -y nginx", "chmod +x /tmp/start.sh", "usermod -aG docker app", "curl https://example.com", "python3 -c 'print(1)'", "echo ok > /tmp/check", "ls /etc/*.conf | head -n 5", "cd /tmp && ./start.sh", "export X=ok\necho $X", "find /tmp -name '*.log'", "echo 'rm -rf /'", "grep rm /tmp/log", "printf '%s' \"$(date)\"")) {
            var d = policy.check(command);
            assertTrue(command + ": " + d.reason(), d.allowed());
            assertEquals(command, d.executionCommand());
            assertFalse(d.readOnly());
        }
    }
    @Test public void explicitDeletionIsBlockedBeforeAnyBranchRuns() {
        for (String command : List.of("rm -rf /", "sudo -n /bin/rm -f -r /tmp/a", "sudo -u root rm /tmp/a", "env X=1 rm /tmp/a", "busybox rm /tmp/a", "xargs -0 rm", "pwd && rm /tmp/a", "true || rmdir /tmp/a", "echo ok\n/bin/unlink /tmp/a", "bash -lc 'rm -rf /tmp/a'", "sh -c 'echo ok; rm /tmp/a'", "find /tmp -delete", "find /tmp -exec rm {} \\;", "rsync -a --delete /a/ /b/", "docker system prune -af", "docker container rm a", "git clean -fd", "echo \"$(rm /tmp/a)\"", "echo `rm /tmp/a`", "r\\m /tmp/a", "r''m /tmp/a", "python3 -c 'import os; os.remove(\"/tmp/a\")'", "python3 -c 'import shutil; shutil.rmtree(\"/tmp/a\")'", "pwsh -Command 'Remove-Item -Recurse C:/tmp/a'"))
            assertFalse(command, policy.check(command).allowed());
    }
    @Test public void diskDestructionAndEmptyCommandsAreBlocked() {
        for (String command : List.of("mkfs.ext4 /dev/sda", "sudo dd if=/dev/zero of=/dev/sda", "wipefs -a /dev/sda", "blkdiscard /dev/sda", " "))
            assertFalse(command, policy.check(command).allowed());
        assertFalse(policy.check(null).allowed());
    }
}
