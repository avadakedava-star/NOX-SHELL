package cn.bugstack.ai.domain.ssh.service.policy;

import java.util.ArrayList;
import java.util.List;

/** 受限 Shell 分析器：识别引号外的连接符，各简单命令仍须独立校验并重新引用。 */
public final class ShellCommandAnalyzer {
    public record Chain(List<List<String>> commands, List<String> operators, boolean writesFile) { }
    private record ParsedCommand(List<String> words, boolean writesFile) { }
    private record OutputRedirect(int end, boolean writesFile) { }

    private static final String MERGE_STDERR = "2>&1";

    public Chain analyzeChain(String command) {
        return analyzeChain(command, false);
    }

    public Chain analyzeQueryChain(String command) {
        return analyzeChain(command, true);
    }

    private Chain analyzeChain(String command, boolean querySyntax) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        if (command.length() > 4096) throw new IllegalArgumentException("命令超过 4096 字符");
        int commandLimit = querySyntax ? 32 : 8;
        List<ParsedCommand> commands = new ArrayList<>();
        List<String> operators = new ArrayList<>();
        char quote = 0;
        int start = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                throw new IllegalArgumentException("不接受多行、控制或隐藏字符，请拆成单独的查询");
            if (quote == '\'') { if (c == '\'') quote = 0; continue; }
            if (c == '\\') {
                if (++i >= command.length()) throw new IllegalArgumentException("不完整的转义字符");
                char escaped = command.charAt(i);
                if (Character.isISOControl(escaped) || Character.getType(escaped) == Character.FORMAT)
                    throw new IllegalArgumentException("不接受转义控制或隐藏字符");
                continue;
            }
            if (quote == '"') { if (c == '"') quote = 0; continue; }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (querySyntax && mergesStderr(command, i)) {
                i += MERGE_STDERR.length() - 1;
                continue;
            }
            if (c != ';' && c != '&' && c != '|') continue;
            commands.add(parse(command.substring(start, i), querySyntax));
            String operator = String.valueOf(c);
            if ((c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c) {
                operator += c; i++;
            } else if (c == '&') throw new IllegalArgumentException("不支持后台执行，请改为前台查询");
            operators.add(operator);
            if (commands.size() >= commandLimit)
                throw new IllegalArgumentException("一次最多组合 " + commandLimit + " 个命令，请分批执行");
            start = i + 1;
        }
        commands.add(parse(command.substring(start), querySyntax));
        int argumentLimit = querySyntax ? 128 : 64;
        var words = commands.stream().map(ParsedCommand::words).toList();
        if (words.stream().mapToInt(List::size).sum() > argumentLimit)
            throw new IllegalArgumentException("参数总数超过 " + argumentLimit);
        return new Chain(words, List.copyOf(operators), commands.stream().anyMatch(ParsedCommand::writesFile));
    }

    public List<String> analyze(String command) {
        return parse(command, false).words();
    }

    private ParsedCommand parse(String command, boolean querySyntax) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        if (command.length() > 4096) throw new IllegalArgumentException("命令超过 4096 字符");
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean present = false;
        boolean writesFile = false;
        for (int index = 0; index < command.length(); index++) {
            char c = command.charAt(index);
            if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                throw new IllegalArgumentException("不接受控制字符、多行命令或隐藏格式字符");
            if (quote == '\'') {
                if (c == '\'') quote = 0; else token.append(c);
                present = true; continue;
            }
            if (c == '\\') {
                if (++index >= command.length()) throw new IllegalArgumentException("不完整的转义字符");
                char escaped = command.charAt(index);
                if (Character.isISOControl(escaped) || Character.getType(escaped) == Character.FORMAT) throw new IllegalArgumentException("不接受转义控制或隐藏字符");
                // POSIX 双引号内，反斜杠只对这些字符转义，其它情况必须保留原反斜杠。
                if (quote == '"' && "\"\\$`".indexOf(escaped) < 0) token.append('\\');
                token.append(escaped); present = true; continue;
            }
            if (querySyntax && c == '$' && index + 1 < command.length() && command.charAt(index + 1) == '?') {
                token.append("$?");
                index++;
                present = true;
                continue;
            }
            if (quote == '"') {
                if (c == '"') quote = 0;
                else if (c == '$' || c == '`') throw new IllegalArgumentException("不接受变量展开或命令替换");
                else token.append(c);
                present = true; continue;
            }
            if (c == '\'' || c == '"') { quote = c; present = true; continue; }
            if (querySyntax && !present && mergesStderr(command, index)) {
                index += MERGE_STDERR.length() - 1;
                continue;
            }
            int redirect = c == '>' ? index : !present && (c == '1' || c == '2') && command.startsWith(">", index + 1) ? index + 1 : -1;
            if (querySyntax && redirect >= 0) {
                var output = outputRedirect(command, redirect);
                if (output != null) {
                    if (present) tokens.add(token.toString());
                    token.setLength(0);
                    present = false;
                    writesFile |= output.writesFile();
                    index = output.end() - 1;
                    continue;
                }
            }
            if (Character.isWhitespace(c)) {
                if (present) { tokens.add(token.toString()); token.setLength(0); present = false; }
            } else {
                if (";&|<>()$`{}*?[]~".indexOf(c) >= 0) throw new IllegalArgumentException("不接受组合命令、重定向、展开或通配语法");
                token.append(c); present = true;
            }
            if (tokens.size() > 64) throw new IllegalArgumentException("参数数量超过限制");
        }
        if (quote != 0) throw new IllegalArgumentException("引号未闭合");
        if (present) tokens.add(token.toString());
        if (tokens.isEmpty() || tokens.size() > 64) throw new IllegalArgumentException("命令或参数数量无效");
        return new ParsedCommand(List.copyOf(tokens), writesFile);
    }
    // 仅合并当前命令的 stderr 和 stdout，写入文件等重定向仍需单独通过校验。
    private boolean mergesStderr(String command, int start) {
        if (!command.startsWith(MERGE_STDERR, start)) return false;
        if (start > 0 && !tokenBoundary(command.charAt(start - 1))) return false;
        int end = start + MERGE_STDERR.length();
        return end == command.length() || tokenBoundary(command.charAt(end));
    }

    private boolean tokenBoundary(char c) {
        return c == ' ' || c == ';' || c == '|' || c == '&';
    }

    // 只识别字面量路径。写入普通文件会标记为变更，动态文件名需要改写。
    private OutputRedirect outputRedirect(String command, int redirect) {
        int start = redirect + (command.startsWith(">>", redirect) ? 2 : 1);
        while (start < command.length() && command.charAt(start) == ' ') start++;
        int end = start;
        while (end < command.length() && command.charAt(end) != ' ') end++;
        String target = command.substring(start, end);
        if (!target.matches("[A-Za-z0-9_./-]+")) return null;
        return new OutputRedirect(end, !target.equals("/dev/null"));
    }

    public static String shellQuote(String token) { return "'" + token.replace("'", "'\"'\"'") + "'"; }
}
