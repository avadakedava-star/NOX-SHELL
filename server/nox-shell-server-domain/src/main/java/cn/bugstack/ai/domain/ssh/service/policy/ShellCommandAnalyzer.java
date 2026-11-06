package cn.bugstack.ai.domain.ssh.service.policy;

import java.util.ArrayList;
import java.util.List;

/** 受限 Shell 分析器：识别引号外的连接符，各简单命令仍须独立校验并重新引用。 */
public final class ShellCommandAnalyzer {
    public record Chain(List<List<String>> commands, List<String> operators) { }

    public Chain analyzeChain(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        if (command.length() > 4096) throw new IllegalArgumentException("命令超过 4096 字符");
        List<List<String>> commands = new ArrayList<>();
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
            if (c != ';' && c != '&' && c != '|') continue;
            commands.add(analyze(command.substring(start, i)));
            String operator = String.valueOf(c);
            if ((c == '&' || c == '|') && i + 1 < command.length() && command.charAt(i + 1) == c) {
                operator += c; i++;
            } else if (c == '&') throw new IllegalArgumentException("不支持后台执行，请改为前台查询");
            operators.add(operator);
            if (commands.size() >= 8) throw new IllegalArgumentException("一次最多组合 8 个查询，请分批执行");
            start = i + 1;
        }
        commands.add(analyze(command.substring(start)));
        if (commands.stream().mapToInt(List::size).sum() > 64) throw new IllegalArgumentException("参数总数超过 64");
        return new Chain(List.copyOf(commands), List.copyOf(operators));
    }

    public List<String> analyze(String command) {
        if (command == null || command.isBlank()) throw new IllegalArgumentException("命令不能为空");
        if (command.length() > 4096) throw new IllegalArgumentException("命令超过 4096 字符");
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean present = false;
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
            if (quote == '"') {
                if (c == '"') quote = 0;
                else if (c == '$' || c == '`') throw new IllegalArgumentException("不接受变量展开或命令替换");
                else token.append(c);
                present = true; continue;
            }
            if (c == '\'' || c == '"') { quote = c; present = true; continue; }
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
        return List.copyOf(tokens);
    }
    public static String shellQuote(String token) { return "'" + token.replace("'", "'\"'\"'") + "'"; }
}
