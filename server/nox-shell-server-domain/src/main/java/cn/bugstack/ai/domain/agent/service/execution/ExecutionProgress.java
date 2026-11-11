package cn.bugstack.ai.domain.agent.service.execution;

import java.util.*;
import java.util.function.LongSupplier;

/** 客观证据与模型解释分开保存。新证据只代表信息变化，不自动代表目标已达成。 */
public final class ExecutionProgress {
    private final LongSupplier clock;
    private final LinkedHashMap<String, Map<String,Object>> evidence = new LinkedHashMap<>();
    private final Set<String> fingerprints = new HashSet<>();
    private final Set<String> reviewed = new HashSet<>();
    private final Map<String,List<String>> criteriaByScope=new HashMap<>();
    private String assessmentScope="root";
    private String objective="", assessment="", nextCheck="";
    private List<String> criteria=List.of(), citations=List.of();
    private long lastEvidenceAt;
    private int unchanged;
    public ExecutionProgress(LongSupplier clock){this.clock=clock;lastEvidenceAt=clock.getAsLong();}
    public synchronized void objective(String text){if(objective.isBlank() && text!=null)objective=text.substring(0,Math.min(4000,text.length()));}
    public synchronized boolean observe(String id,String tool,String status,String fingerprint){
        if(!Set.of("RETURNED","REPORTED_FAILURE").contains(status) || tool.equals("reportProgress")
                || tool.toLowerCase(Locale.ROOT).contains("dispatch") || tool.startsWith("ssh") && tool.endsWith("Agent"))return false;
        String digest;
        try{digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        boolean fresh=fingerprints.size()<4096 && fingerprints.add(digest);
        if(fresh){lastEvidenceAt=clock.getAsLong();unchanged=0;}else unchanged++;
        evidence.put(id,Map.of("executionId",id,"tool",tool,"status",status,"newInformation",fresh));
        while(evidence.size()>128)evidence.remove(evidence.keySet().iterator().next());
        return fresh;
    }
    public synchronized Map<String,Object> review(List<String> acceptance,List<String> ids,String conclusion,String verification){
        return review("root",acceptance,ids,conclusion,verification);
    }
    public synchronized Map<String,Object> review(String scope,List<String> acceptance,List<String> ids,String conclusion,String verification){
        if(acceptance.isEmpty() || acceptance.size()>12 || acceptance.stream().anyMatch(s->s.isBlank() || s.length()>500)
                || ids.isEmpty() || ids.size()>12 || conclusion.isBlank() || conclusion.length()>2000 || verification.isBlank() || verification.length()>1000)
            return Map.of("success",false,"output","请给出验收条件、真实执行记录编号、判断依据和下一项验证；不能用自述代替证据");
        var existing=criteriaByScope.getOrDefault(scope,List.of());
        if(!existing.isEmpty() && !existing.equals(acceptance))return Map.of("success",false,"output","验收条件已记录，不得在执行中降低或替换");
        if(!criteriaByScope.containsKey(scope) && criteriaByScope.size()>=128)return Map.of("success",false,"output","子任务评估范围已满，请汇总已有结果");
        if(ids.stream().anyMatch(id->!evidence.containsKey(id)))return Map.of("success",false,"output","引用的证据不存在于当前请求中，不能据此判断进展");
        if(ids.stream().noneMatch(id->!reviewed.contains(scope+"|"+id) && Boolean.TRUE.equals(evidence.get(id).get("newInformation"))))
            return Map.of("success",false,"output","这些记录没有未评估的新信息；重复解释不能续期，请检查停滞原因");
        criteriaByScope.put(scope,List.copyOf(acceptance));if(scope.equals("root"))criteria=List.copyOf(acceptance);
        citations=List.copyOf(ids);ids.forEach(id->reviewed.add(scope+"|"+id));assessment=conclusion;nextCheck=verification;assessmentScope=scope;
        return Map.of("success",true,"output","证据引用已核对，解释作为模型判断保存；不代表已完成，也不会单独延长执行时间", "progress",snapshot());
    }
    public synchronized long lastEvidenceAt(){return lastEvidenceAt;}
    public synchronized int unchanged(){return unchanged;}
    public synchronized Map<String,Object> snapshot(){
        var value=new LinkedHashMap<String,Object>();value.put("objective",objective);value.put("acceptanceCriteria",criteria);
        value.put("lastEvidenceAt",lastEvidenceAt);value.put("unchangedResults",unchanged);
        value.put("evidence",new ArrayList<>(evidence.values()).stream().skip(Math.max(0,evidence.size()-12)).toList());
        value.put("modelAssessment",assessment);value.put("assessmentEvidenceIds",citations);value.put("nextVerification",nextCheck);
        value.put("assessmentScope",assessmentScope);value.put("scopeAcceptanceCriteria",criteriaByScope.getOrDefault(assessmentScope,List.of()));
        value.put("assessmentVerified",false);return value;
    }
}
