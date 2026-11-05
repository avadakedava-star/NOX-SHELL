package cn.bugstack.ai.domain.ssh.service.policy;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 只有这里明确列出的变更可申请审批；审批不是任意 Shell 的放行开关。 */
public final class ChangeCommandPolicy {
    private ChangeCommandPolicy() { }
    public static CommandPolicyDecision check(List<String> tokens) {
        String program=tokens.get(0).substring(tokens.get(0).lastIndexOf('/')+1);
        List<String> args=tokens.subList(1,tokens.size());
        boolean valid=false;
        if (Set.of("apt","apt-get").contains(program) && !args.isEmpty()) {
            if (args.get(0).equals("update")) valid=args.size()==1;
            else if (args.get(0).equals("install")) valid=packageInstall(args.subList(1,args.size()));
        }
        if (Set.of("yum","dnf","apk").contains(program) && !args.isEmpty()
                && args.get(0).equals(program.equals("apk") ? "add" : "install")) valid=packageInstall(args.subList(1,args.size()));
        if (program.equals("systemctl")) {
            var filtered=args.stream().filter(a->Set.of("--no-pager","--system","--user").contains(a)==false).toList();
            if (filtered.size()==1 && filtered.get(0).equals("daemon-reload")) valid=true;
            else if (filtered.size()>=2 && Set.of("start","stop","restart","reload","enable","disable").contains(filtered.get(0)))
                valid=filtered.stream().skip(1).allMatch(a->a.matches("[A-Za-z0-9_][A-Za-z0-9_.@:-]*"));
        }
        if (program.equals("docker") && args.size()>=2 && Set.of("start","stop","restart").contains(args.get(0)))
            valid=args.stream().skip(1).allMatch(a->a.matches("[A-Za-z0-9][A-Za-z0-9_.-]*"));
        if (!valid) return CommandPolicyDecision.deny("C006", "该变更尚未开放审批；目前支持指定软件包安装、服务启停/重启和容器启停/重启，配置请使用 updateConfigFile");
        return new CommandPolicyDecision("REQUIRE_APPROVAL","C006","服务器变更需要用户逐条确认；批准前不会执行",false,canonical(tokens));
    }
    private static boolean packageInstall(List<String> args) {
        return args.stream().anyMatch(a->!a.startsWith("-")) && args.stream().allMatch(a->
                Set.of("-y","--yes","--no-install-recommends").contains(a) || a.matches("[A-Za-z0-9][A-Za-z0-9+.-]*(?:=[A-Za-z0-9:~+._-]+)?"));
    }
    public static String canonical(List<String> tokens) {
        return "command -p " + tokens.stream().map(ShellCommandAnalyzer::shellQuote).collect(Collectors.joining(" "));
    }
}
