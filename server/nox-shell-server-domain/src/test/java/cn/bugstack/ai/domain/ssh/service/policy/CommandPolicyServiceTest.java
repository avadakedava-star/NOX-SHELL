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
    @org.junit.Test public void approvalModeSeparatesQueriesFromChanges() {
        var policy = new CommandPolicyService();
        for (String query : List.of("df -h; free -h", "systemctl status nginx", "docker ps",
                "cat /etc/os-release 2>/dev/null; echo '---'; command -v apt apt-get yum dnf 2>/dev/null; echo '---'; id -u; whoami",
                "sudo -n true; echo \"sudo_rc=$?\"; dpkg -l nginx 2>/dev/null | tail -n +6; command -v nginx || echo 'missing'; systemctl is-active nginx 2>/dev/null || true")) {
            org.junit.Assert.assertEquals("ALLOW", policy.withApproval(query).decision());
        }
        for (String command : List.of("docker start demo", "docker pull nginx:alpine", "apt-get install -y nginx",
                "curl https://example.com/app.deb -o app.deb", "df -h; docker start demo",
                "echo ok >/tmp/change", "echo ok >/dev/null-copy",
                "sudo -n systemctl start nginx 2>/dev/null", "dpkg -i package.deb",
                "command -v apt && sudo -n apt-get install nginx")) {
            org.junit.Assert.assertEquals("REQUIRE_APPROVAL", policy.withApproval(command).decision());
            org.junit.Assert.assertEquals("ALLOW", policy.check(command).decision());
        }
    }
    @Test public void fullServiceInspectionSeparatesNginxConfigTest() {
        String query = """
                printf '\\n=== 1) 安装情况 ===\\n'; which nginx; echo "which_exit=$?"; nginx -v 2>&1; echo "v_exit=$?"; \
                dpkg -l 2>/dev/null | grep -i nginx; echo "dpkg_exit=$?"; printf '\\n=== 2) 服务状态 ===\\n'; \
                systemctl is-active nginx 2>&1; echo "active_exit=$?"; systemctl is-enabled nginx 2>&1; echo "enabled_exit=$?"; \
                systemctl status nginx --no-pager -l 2>&1 | head -n 25; printf '\\n=== 3) 配置校验 ===\\n'; nginx -t 2>&1; echo "t_exit=$?"; \
                printf '\\n=== 4) 最近日志 ===\\n'; journalctl -u nginx -n 30 --no-pager 2>&1; \
                printf '\\n=== 5) 端口监听 ===\\n'; ss -lntp 2>/dev/null | grep -E ':80|:443'; echo "ss_grep_exit=$?"; ss -lntp 2>/dev/null | head -n 15
                """.stripTrailing();
        var mixed = policy.withApproval(query);
        assertEquals("REWRITE_REQUIRED", mixed.decision());
        assertTrue(mixed.reason().contains("nginx -t"));
        assertEquals("REQUIRE_APPROVAL", policy.withApproval("nginx -t 2>&1").decision());
        String onlyQueries = query.replace("nginx -t 2>&1; echo \"t_exit=$?\"; ", "");
        assertEquals("ALLOW", policy.withApproval(onlyQueries).decision());
    }

    @Test public void unrecognizedSyntaxAsksForRewriteInsteadOfApproval() {
        for (String command : List.of("echo \"$(touch /tmp/change)\"", "journalctl --rotate",
                "dpkg -l nginx --install package.deb", "command apt-get install nginx",
                "nginx -v 2>&1extra", "nginx -v 3>&1", "echo ok >\"$TARGET\"")) {
            assertEquals(command, "REWRITE_REQUIRED", policy.withApproval(command).decision());
            assertEquals(command, "ALLOW", policy.check(command).decision());
        }
        assertEquals("DENY", policy.withApproval("which nginx; rm -rf /tmp/demo").decision());
    }

    @Test public void nginxInspectionWithMergedStderrDoesNotRequireApproval() {
        String query = """
                printf '\\n=== nginx 命令位置 ===\\n'; command -v nginx || echo "未找到 nginx 可执行文件"; \
                printf '\\n=== nginx 版本 ===\\n'; nginx -v 2>&1 || echo "nginx -v 执行失败"; \
                printf '\\n=== systemd 服务状态 ===\\n'; systemctl is-active nginx 2>&1; \
                printf '\\n=== 服务是否启用 ===\\n'; systemctl is-enabled nginx 2>&1; \
                printf '\\n=== 编译参数(前3行) ===\\n'; nginx -V 2>&1 | head -n 3
                """.stripTrailing();
        var allowed = policy.withApproval(query);
        assertEquals("ALLOW", allowed.decision());
        assertEquals(query, allowed.executionCommand());
        assertEquals("ALLOW", policy.withApproval("nginx -V 2>&1|head -n 3").decision());
        for (String change : List.of("nginx -s reload 2>&1", "nginx -v 2>&1 >/tmp/report",
                "nginx -v 2>&1; docker start demo")) {
            assertEquals(change, "REQUIRE_APPROVAL", policy.withApproval(change).decision());
        }
    }
}
