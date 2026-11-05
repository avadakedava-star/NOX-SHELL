package cn.bugstack.ai.domain.agent.service.approval;

import java.util.Map;

public record CommandApproval(String id,String userId,String sessionId,String terminalSessionId,String connectionId,
        String host,String username,String kind,String command,String executionCommand,
        String path,String expectedContent,String content,long createdAt,long expiresAt,String status,String result) {
    public CommandApproval state(String state,String result) {
        return new CommandApproval(id,userId,sessionId,terminalSessionId,connectionId,host,username,kind,command,
                executionCommand,path,expectedContent,content,createdAt,expiresAt,state,result);
    }
    public Map<String,Object> view() {
        var view=new java.util.LinkedHashMap<String,Object>();
        view.put("id",id); view.put("userId",userId); view.put("sessionId",sessionId); view.put("host",host);
        view.put("username",username); view.put("kind",kind); view.put("command",command);
        view.put("terminalSessionId",terminalSessionId); view.put("connectionId",connectionId);
        view.put("executionCommand",executionCommand); view.put("path",path); view.put("expectedContent",expectedContent);
        view.put("content",content); view.put("expiresAt",expiresAt); view.put("status",status); view.put("result",result);
        return view;
    }
}
