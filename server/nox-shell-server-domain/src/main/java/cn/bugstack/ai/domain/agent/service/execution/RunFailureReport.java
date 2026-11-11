package cn.bugstack.ai.domain.agent.service.execution;

import cn.bugstack.ai.domain.agent.service.audit.ToolExecutionRecord;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import java.util.regex.Pattern;

/** 审计事实供模型分析，explanation 是用户可直接阅读的简短兜底。 */
public record RunFailureReport(String reason, String cause, List<String> completed, List<String> unfinished, String explanation) {
    public static RunFailureReport from(String reason, String cause, List<ToolExecutionRecord> records) {
        var done=new ArrayList<String>(); var pending=new ArrayList<String>();
        var mapper=new ObjectMapper();
        String failureType="", failedCommand="";
        long timeoutMs=30000;
        boolean containerCreated=false;
        for(var record: records) {
            if(record.toolName().equals("reportProgress"))continue;
            // 派发记录含整段任务和子工具结果，不把它重复当作命令证据。
            if(record.toolName().toLowerCase(Locale.ROOT).contains("dispatch") || record.toolName().startsWith("ssh") && record.toolName().endsWith("Agent")) continue;
            String command=record.arguments(), detail=record.error()==null ? "" : record.error();
            try {
                var args=mapper.readTree(record.arguments());
                if(args.has("command"))command=args.path("command").asText();
            } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { /* 兼容普通文本参数。 */ }
            try {
                var result=mapper.readTree(record.result()==null ? "{}" : record.result());
                detail=result.path("stderr").asText();
                if(detail.isBlank()) detail=result.path("output").asText();
                if(record.status().equals("RETURNED") && command.contains("docker run")
                        && result.path("stdout").asText(result.path("output").asText()).trim().matches("[a-f0-9]{64}")) containerCreated=true;
                if(!record.status().equals("RETURNED")) {
                    failureType=result.path("failureType").asText();failedCommand=command;
                    if(detail.contains("No such file or directory"))failureType="FILE_NOT_FOUND";
                    timeoutMs=result.path("timeoutMs").asLong(30000);
                }
                detail="exitCode="+result.path("exitCode")+", commandDispatched="+result.path("commandDispatched")+", failureType="+result.path("failureType").asText()+"; "+detail;
            } catch(com.fasterxml.jackson.core.JsonProcessingException ignored) { detail=record.result(); }
            if(record.error()!=null) detail=record.error()+"; "+detail;
            String fact=record.sourceAgent()+" / "+record.toolName()+" "+record.arguments()+" ["+record.status()+"] "+detail;
            if(fact.length()>1800)fact=fact.substring(0,1800)+"…";
            if(record.status().equals("RETURNED"))done.add(fact);else pending.add(fact);
        }
        String explanation=explain(reason,cause,failureType,failedCommand,timeoutMs,containerCreated,!done.isEmpty());
        return new RunFailureReport(reason,cause,tail(done),tail(pending),explanation);
    }
    private static List<String> tail(List<String> list){return List.copyOf(list.subList(Math.max(0,list.size()-8),list.size()));}
    private static String explain(String reason,String cause,String failure,String command,long timeoutMs,boolean containerCreated,boolean hasResults){
        String progress=containerCreated ? "容器已经创建，卡在后面的检查步骤，还没有确认它是否正常运行、能否连接。" : hasResults ? "前面的部分步骤已有返回结果，但最后的确认没有完成。" : "这次任务还没有完成。";
        String problem;
        if(failure.equals("COMMAND_TIMEOUT")) {
            var sleep=Pattern.compile("(?:^|[;\\s])sleep\\s+(\\d+)(?:s)?(?:[;\\s]|$)").matcher(command);
            problem="最后一条命令超过了等待时间，没有拿到完整结果。这不等于安装失败。";
            if(sleep.find() && Long.parseLong(sleep.group(1))*1000>=timeoutMs)
                problem="这条检查安排先等待 "+sleep.group(1)+" 秒，但本次命令总时限只有 "+timeoutMs/1000+" 秒，因此没能完成后续检查。";
        } else if(failure.equals("FILE_NOT_FOUND")) problem="最后一步要读取的文件不存在，不是因为没有读取权限。";
        else if(failure.equals("PERMISSION_DENIED")) problem="最后一步没有足够权限，无法完成检查。";
        else if(failure.equals("COMMAND_NOT_FOUND")) problem="最后一步需要的程序没有找到，可能尚未安装或路径不正确。";
        else problem=switch(reason){
            case "REQUEST_TIMEOUT" -> "这轮任务超过了等待时限，已停止继续执行。";
            case "PROGRESS_STALLED", "PROGRESS_REVIEW_REQUIRED" -> "连续检查未取得新的有效信息，已暂停后续操作；这不代表远端任务已经失败或停止。";
            case "RESOURCE_BUDGET" -> "达到执行资源的最终保护上限，已保存已有结果；后台任务需要单独查询状态。";
            case "RESULT_UNKNOWN" -> "命令可能已经执行，但没有取得完整结果，暂时无法确认最终状态。";
            case "CONSECUTIVE_FAILURE_LIMIT" -> "连续几次操作都失败了，已停止继续尝试。";
            case "POLICY_BLOCKED", "POLICY_REWRITE_LIMIT" -> "同一操作多次被安全规则拒绝，已停止继续尝试。";
            default -> {
                String error=cause==null ? "" : cause.toLowerCase(Locale.ROOT);
                if(error.contains("连接") || error.contains("connection")) yield "与模型服务的连接中断，没能收到最后的答复。";
                if(error.contains("timeout") || error.contains("超时")) yield "模型服务未在规定时间内返回答复。";
                if(error.contains("未生成最终答复")) yield "模型停止输出，但没有给出最终答复。";
                yield "执行过程中发生异常，没能取得完整结果；具体原因暂时无法确认，需要查看最后一步的执行详情。";
            }
        };
        String next=containerCreated ? "下一步只需要检查现有容器的状态、启动日志和连接情况，不要重新创建容器。" : "下一步先核查最后一步的实际结果，再决定是否继续；不要直接重复可能已经执行的修改。";
        return progress+"\n\n"+problem+"\n\n"+next;
    }
    /** 内部分析材料，不直接展示给用户。 */
    public String modelFacts(){return "停止原因："+reason+"；"+cause+"\n已返回的执行记录：\n"+String.join("\n",completed)+"\n未完成或待核查记录：\n"+String.join("\n",unfinished);}
    public String fallback(){return explanation;}
}
