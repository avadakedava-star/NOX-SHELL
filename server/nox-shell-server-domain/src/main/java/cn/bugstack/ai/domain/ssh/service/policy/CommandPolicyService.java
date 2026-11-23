package cn.bugstack.ai.domain.ssh.service.policy;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** 高权限沿用危险操作拦截，默认权限额外区分只读查询和需审批的变更。 */
@Service
public class CommandPolicyService {
    private static final Set<String> DELETE = Set.of("rm", "rm-rf", "rmdir", "unlink", "shred", "srm", "del", "erase", "remove-item", "rd");
    private static final Set<String> DISK = Set.of("dd", "wipefs", "shred", "blkdiscard", "mkfs", "mke2fs", "newfs", "format");
    private static final Set<String> WRAPPERS = Set.of("sudo", "doas", "env", "command", "exec", "nohup", "timeout", "nice", "ionice", "busybox", "xargs", "watch");
    private static final Set<String> SHELLS = Set.of("sh", "bash", "dash", "zsh", "ksh", "su");
    private static final Pattern SCRIPT_DELETE = Pattern.compile("(?i)(?:os\\s*\\.\\s*(?:remove|unlink|rmdir)|shutil\\s*\\.\\s*rmtree|\\.\\s*(?:unlink|rmdir|rmtree)|fs\\s*\\.\\s*(?:rm|rmSync|unlink|unlinkSync|rmdir|rmdirSync))\\s*\\(");

    public CommandPolicyDecision check(String command) {
        if (command == null || command.isBlank()) return CommandPolicyDecision.deny("C001", "命令不能为空");
        String rule = inspect(command, 0);
        if (rule != null) return CommandPolicyDecision.deny(rule, "禁止执行删除、清理数据或磁盘破坏操作；整条命令均未发送");
        // 默认按可能写入处理，防止将任意脚本误认作只读而自动重放。
        return new CommandPolicyDecision("ALLOW", "C000", "未命中危险操作黑名单，直接执行", readOnly(command), command);
    }

    public CommandPolicyDecision withApproval(String command) {
        var decision = check(command);
        if (!decision.allowed()) return decision;

        ShellCommandAnalyzer.Chain chain;
        try {
            chain = new ShellCommandAnalyzer().analyzeQueryChain(command);
        } catch (IllegalArgumentException error) {
            return CommandPolicyDecision.rewrite("C010", "默认权限无法确认这段 Shell 的影响：" + error.getMessage()
                    + "。请拆成明确的查询或单独提交必要的变更");
        }
        var commands = chain.commands();
        boolean configTest = commands.stream().anyMatch(this::isNginxConfigTest);
        if (configTest && commands.size() > 1) {
            return CommandPolicyDecision.rewrite("C010", "nginx -t 会尝试打开配置引用的文件。"
                    + "请先单独查询安装、服务、日志或端口；原任务明确要求配置校验时，再单独提交 nginx -t");
        }
        if (commands.stream().anyMatch(words -> !isReadOnlyQuery(words) && !isKnownChange(words) && !isNginxConfigTest(words))) {
            return CommandPolicyDecision.rewrite("C010", "默认权限无法确认这段命令的用途。"
                    + "请改用明确的只读查询，或将已知软件安装、容器启停等变更单独提交");
        }
        if (!configTest && !chain.writesFile() && commands.stream().allMatch(this::isReadOnlyQuery)) return decision;
        return new CommandPolicyDecision("REQUIRE_APPROVAL", "C006", configTest
                ? "nginx -t 会尝试打开配置引用的文件，请确认是否需要执行配置校验"
                : "此操作将进行变更或下载，请批准后执行", false, command);
    }

    private static final Set<String> QUERY_PROGRAMS = Set.of(
            "pwd", "whoami", "id", "uname", "uptime", "df", "free", "ls", "cat", "head",
            "tail", "wc", "stat", "nproc", "ps", "pgrep", "grep", "echo", "printf", "true", "false");
    private static final Set<String> SYSTEMCTL_QUERIES = Set.of(
            "status", "show", "is-active", "is-enabled", "is-system-running",
            "list-units", "list-unit-files", "list-timers", "list-sockets");
    private static final Set<String> DOCKER_QUERIES = Set.of("ps", "inspect", "logs", "images", "stats", "version", "info");

    private boolean readOnly(String command) {
        try {
            return new ShellCommandAnalyzer().analyzeChain(command).commands().stream().allMatch(words ->
                    Set.of("pwd", "whoami", "id", "uname", "uptime", "df", "free", "ls", "cat",
                            "head", "tail", "wc", "stat", "nproc").contains(base(words.get(0))));
        } catch (IllegalArgumentException ignored) { return false; }
    }

    private static final Set<String> PACKAGE_TOOLS = Set.of("apt", "apt-get", "dnf", "yum", "apk", "zypper", "pacman");
    private static final Set<String> PACKAGE_CHANGES = Set.of("update", "install", "upgrade", "remove", "purge", "reinstall",
            "download", "add", "del", "dist-upgrade", "full-upgrade", "-S", "-Sy", "-Syu");
    private static final Set<String> SERVICE_CHANGES = Set.of("start", "stop", "restart", "reload", "reload-or-restart",
            "enable", "disable", "mask", "unmask", "daemon-reload");
    private static final Set<String> DOCKER_CHANGES = Set.of("start", "stop", "restart", "run", "create", "pull", "build",
            "load", "save", "push", "up", "down", "pause", "unpause", "update");
    private static final Set<String> FILE_CHANGES = Set.of("mkdir", "touch", "cp", "mv", "ln", "tee", "chmod", "chown", "chgrp", "install");

    private List<String> withoutNonInteractiveSudo(List<String> words) {
        int start = 0;
        if (words.size() > 2 && words.get(0).equals("sudo") && words.get(1).equals("-n")) {
            start = 2;
            if (words.get(start).equals("--")) start++;
            if (start < words.size() && words.get(start).equals("DEBIAN_FRONTEND=noninteractive")) start++;
        }
        return words.subList(start, words.size());
    }

    private boolean isNginxConfigTest(List<String> words) {
        var command = withoutNonInteractiveSudo(words);
        return command.equals(List.of("nginx", "-t"))
                || command.equals(List.of("nginx", "-t", "-q")) || command.equals(List.of("nginx", "-q", "-t"));
    }

    private boolean isKnownChange(List<String> words) {
        var command = withoutNonInteractiveSudo(words);
        if (command.isEmpty()) return false;
        String program = command.get(0);
        if (FILE_CHANGES.contains(program) || Set.of("curl", "wget").contains(program)) return true;
        if (program.equals("nginx"))
            return command.size() == 1 || command.size() == 3 && command.get(1).equals("-s")
                    && Set.of("reload", "stop", "quit", "reopen").contains(command.get(2));
        if (command.size() < 2) return false;
        String operation = command.get(1);
        if (PACKAGE_TOOLS.contains(program))
            return command.subList(1, command.size()).stream().filter(arg -> !arg.equals("-y") && !arg.equals("--yes"))
                    .findFirst().filter(PACKAGE_CHANGES::contains).isPresent();
        if (program.equals("dpkg")) return Set.of("-i", "--install", "-r", "--remove", "--configure").contains(operation);
        if (program.equals("systemctl"))
            return SERVICE_CHANGES.contains(operation) || operation.equals("--no-pager") && command.size() > 2
                    && SERVICE_CHANGES.contains(command.get(2));
        if (program.equals("docker"))
            return DOCKER_CHANGES.contains(operation) || Set.of("container", "image", "compose").contains(operation)
                    && command.size() > 2 && DOCKER_CHANGES.contains(command.get(2));
        return false;
    }

    private boolean journalQuery(List<String> words) {
        for (int index = 1; index < words.size(); index++) {
            String option = words.get(index);
            if (Set.of("--no-pager", "--quiet", "-q", "--reverse", "-r").contains(option)) continue;
            if (option.matches("(?:--lines=|-n)[0-9]+") || option.matches("--unit=[A-Za-z0-9_][A-Za-z0-9_.@:-]*")) continue;
            if (++index >= words.size()) return false;
            String argument = words.get(index);
            if (Set.of("-n", "--lines").contains(option) && argument.matches("[0-9]+")) continue;
            if (Set.of("-u", "--unit").contains(option) && argument.matches("[A-Za-z0-9_][A-Za-z0-9_.@:-]*")) continue;
            if (Set.of("--since", "--until", "-S", "-U").contains(option) && !argument.startsWith("-")) continue;
            return false;
        }
        return true;
    }

    private boolean isReadOnlyQuery(List<String> words) {
        String program = words.get(0);
        if (QUERY_PROGRAMS.contains(program)) return true;
        if (program.equals("journalctl")) return journalQuery(words);
        if (words.size() < 2) return false;
        if (program.equals("which")) {
            int start = words.get(1).equals("-a") ? 2 : 1;
            return words.size() > start && words.subList(start, words.size()).stream()
                    .allMatch(path -> path.matches("[A-Za-z0-9_./][A-Za-z0-9_./+-]*"));
        }
        if (program.equals("nginx")) return words.size() == 2 && Set.of("-v", "-V").contains(words.get(1));
        if (program.equals("sudo")) return words.equals(List.of("sudo", "-n", "true"));
        if (program.equals("command"))
            return words.size() >= 3 && Set.of("-v", "-V").contains(words.get(1))
                    && words.subList(2, words.size()).stream().allMatch(arg -> arg.matches("[A-Za-z0-9_./][A-Za-z0-9_./+-]*"));
        if (program.equals("dpkg") || program.equals("dpkg-query"))
            return Set.of("-l", "--list", "-s", "--status", "-L", "--listfiles").contains(words.get(1))
                    && words.subList(2, words.size()).stream().allMatch(arg -> arg.matches("[A-Za-z0-9][A-Za-z0-9+.:_-]*"));
        if (program.equals("ss"))
            return words.subList(1, words.size()).stream().allMatch(arg -> arg.matches("-[atunlpHO4s6]+"));
        int optionIndex = program.equals("systemctl") && words.get(1).equals("--no-pager") ? 2 : 1;
        if (words.size() <= optionIndex) return false;
        String operation = words.get(optionIndex);
        return (program.equals("systemctl") && SYSTEMCTL_QUERIES.contains(operation)
                || program.equals("docker") && DOCKER_QUERIES.contains(operation))
                && words.subList(optionIndex + 1, words.size()).stream().noneMatch(
                        arg -> arg.equals("--kill") || arg.startsWith("--host") || arg.startsWith("--root")
                                || arg.startsWith("--context") || arg.startsWith("--machine"));
    }

    private String inspect(String text, int depth) {
        if (depth > 32) return "C002";
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        boolean present = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '\'' && i + 1 < text.length()) {
                if (text.charAt(i + 1) != '\n') { word.append(text.charAt(i + 1)); present = true; }
                i++; continue;
            }
            if (quote != '\'') {
                // 双引号内的命令替换仍会执行，递归预检，单引号内则为普通文字。
                if (c == '$' && i + 1 < text.length() && text.charAt(i + 1) == '(') {
                    int end = substitutionEnd(text, i + 2, '(', ')');
                    String rule = inspect(text.substring(i + 2, end), depth + 1);
                    if (rule != null) return rule;
                    word.append("$(substitution)"); present = true; i = end; continue;
                }
                if (c == '`') {
                    int end = text.indexOf('`', i + 1);
                    if (end < 0) end = text.length();
                    String rule = inspect(text.substring(i + 1, end), depth + 1);
                    if (rule != null) return rule;
                    word.append("substitution"); present = true; i = end; continue;
                }
            }
            if (quote != 0) {
                if (c == quote) quote = 0; else word.append(c);
                present = true; continue;
            }
            if (c == '\'' || c == '"') { quote = c; present = true; continue; }
            if (Character.isWhitespace(c) || ";&|()<>".indexOf(c) >= 0) {
                if (present) { words.add(word.toString()); word.setLength(0); present = false; }
                if (c == '\n' || ";&|()".indexOf(c) >= 0) {
                    String rule = inspectWords(words, depth);
                    if (rule != null) return rule;
                    words.clear();
                }
            } else { word.append(c); present = true; }
        }
        if (present) words.add(word.toString());
        return inspectWords(words, depth);
    }

    private static int substitutionEnd(String text, int start, char open, char close) {
        int level = 1; char quote = 0;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\' && quote != '\'') { i++; continue; }
            if (quote != 0) { if (c == quote) quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == open) level++;
            if (c == close && --level == 0) return i;
        }
        return text.length();
    }

    private String inspectWords(List<String> words, int depth) {
        for (int i = 0; i < words.size(); i++) {
            String token = words.get(i);
            if (token.matches("[A-Za-z_][A-Za-z0-9_]*=.*") || Set.of("if", "then", "else", "elif", "do", "!", "{", "}").contains(token)) continue;
            String program = base(token);
            if (DELETE.contains(program)) return "C002";
            if (DISK.contains(program) || program.startsWith("mkfs.")) return "C003";
            List<String> args = words.subList(i + 1, words.size());
            if (program.equals("find") && args.contains("-delete")) return "C002";
            if (program.equals("rsync") && args.stream().anyMatch(a -> a.equals("--delete") || a.startsWith("--delete-"))) return "C002";
            if ((program.equals("docker") || program.equals("podman")) && args.stream().anyMatch(a -> Set.of("rm", "rmi", "prune").contains(a))) return "C002";
            if (program.equals("git") && args.contains("clean")) return "C002";
            if (program.equals("find")) {
                for (int j = 0; j < args.size(); j++) if (Set.of("-exec", "-execdir", "-ok", "-okdir").contains(args.get(j))) {
                    String rule = inspectWords(args.subList(j + 1, args.size()), depth + 1);
                    if (rule != null) return rule;
                }
            }
            if (WRAPPERS.contains(program)) {
                while (i + 1 < words.size() && words.get(i + 1).startsWith("-")) {
                    String option = words.get(++i);
                    if (Set.of("-u", "-g", "-h", "-p", "-C", "-T", "-R", "-D", "--user", "--group", "--host", "-I", "-L", "-P", "-n", "--max-args", "--replace").contains(option)
                            && !(program.equals("sudo") && option.equals("-n")) && !(program.equals("command") && option.equals("-p"))
                            && i + 1 < words.size()) i++;
                }
                if (Set.of("timeout", "nice", "ionice", "watch").contains(program)
                        && i + 1 < words.size() && words.get(i + 1).matches("[0-9.]+[smhd]?")) i++;
                continue;
            }
            if (SHELLS.contains(program) || program.equals("eval")) {
                if (program.equals("eval")) return inspect(String.join(" ", args), depth + 1);
                for (int j = 0; j + 1 < args.size(); j++) if (args.get(j).matches("-[A-Za-z]*c[A-Za-z]*")) {
                    String rule = inspect(args.get(j + 1), depth + 1);
                    if (rule != null) return rule;
                }
            }
            if (program.startsWith("python") || Set.of("node", "nodejs", "perl", "ruby", "powershell", "pwsh").contains(program)) {
                for (String arg : args) {
                    if (SCRIPT_DELETE.matcher(arg).find()) return "C002";
                    if (Set.of("powershell", "pwsh").contains(program)) {
                        String rule = inspect(arg, depth + 1); if (rule != null) return rule;
                    }
                }
            }
            // 普通命令的参数不是可执行程序，如 echo 'rm -rf /'、grep rm file。
            return null;
        }
        return null;
    }
    private static String base(String token) {
        int slash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return token.substring(slash + 1).toLowerCase(Locale.ROOT);
    }
}
