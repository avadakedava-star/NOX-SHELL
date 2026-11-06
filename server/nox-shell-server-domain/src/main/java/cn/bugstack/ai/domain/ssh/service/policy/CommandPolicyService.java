package cn.bugstack.ai.domain.ssh.service.policy;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.regex.Pattern;

/** 删除/磁盘破坏黑名单。其它命令保留原始 Shell 语义，不再使用查询白名单或审批。 */
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

    private boolean readOnly(String command) {
        try {
            return new ShellCommandAnalyzer().analyzeChain(command).commands().stream().allMatch(words ->
                    Set.of("pwd", "whoami", "id", "uname", "uptime", "df", "free", "ls", "cat", "head", "tail", "wc", "stat", "nproc").contains(base(words.get(0))));
        } catch (IllegalArgumentException ignored) { return false; }
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
