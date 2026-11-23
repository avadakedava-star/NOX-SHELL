package cn.bugstack.ai.domain.agent.service.execution;

import java.util.*;
import java.util.function.LongSupplier;

/**
 * 请求级进度账本：客观证据与模型自述分开保存，新证据只代表信息发生变化，不自动代表目标已达成。
 *
 * <p>核心约束：
 * <ul>
 *   <li>证据只能来自真实执行结果，且按内容指纹去重；重复结果不计为新信息</li>
 *   <li>验收条件在某个 scope 首次提交后即冻结，执行过程中不允许降低或替换</li>
 *   <li>模型评估必须引用本请求内存在、且尚未被评估过的新证据，自述不能刷新停滞计时</li>
 * </ul>
 */
public final class ExecutionProgress {
    private final LongSupplier clock;
    /** 执行编号 → 证据摘要；只保留最近 128 条，避免长会话无限增长。 */
    private final LinkedHashMap<String, Map<String,Object>> evidence = new LinkedHashMap<>();
    /** 结果内容 SHA-256 集合，用于判断这次返回是否真的产生新信息。 */
    private final Set<String> fingerprints = new HashSet<>();
    /** 已评估证据标记（scope|id），同一证据在同一 scope 只允许引用一次。 */
    private final Set<String> reviewed = new HashSet<>();
    /** 各评估范围首次提交的验收条件，提交后冻结。 */
    private final Map<String,List<String>> criteriaByScope=new HashMap<>();
    private String assessmentScope="root";
    private String objective="", assessment="", nextCheck="";
    private List<String> criteria=List.of(), citations=List.of();
    /** 最近一次产生新证据的时间，停滞判定与请求超时都以它为基准。 */
    private long lastEvidenceAt;
    /** 连续没有新信息的次数，达到配置阈值后触发进展复核。 */
    private int unchanged;
    public ExecutionProgress(LongSupplier clock){this.clock=clock;lastEvidenceAt=clock.getAsLong();}
    public synchronized void objective(String text){if(objective.isBlank() && text!=null)objective=text.substring(0,Math.min(4000,text.length()));}
    public synchronized boolean observe(String id,String tool,String status,String fingerprint){
        // 只有真实命令返回/上报失败才算证据；进度报告、子 Agent 调度和 sshAgent 包装结果会被排除，防止自述或空壳调用刷证据。
        if(!Set.of("RETURNED","REPORTED_FAILURE").contains(status) || tool.equals("reportProgress")
                || tool.toLowerCase(Locale.ROOT).contains("dispatch") || tool.startsWith("ssh") && tool.endsWith("Agent"))return false;
        String digest;
        // 对结果内容做 SHA-256 指纹：内容相同即视为没有新信息，而不是看执行编号。
        try{digest=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException error){throw new IllegalStateException(error);}
        // 指纹集合有上限，超过后不再接受新指纹（避免内存失控），但重复结果仍会累加 unchanged。
        boolean fresh=fingerprints.size()<4096 && fingerprints.add(digest);
        if(fresh){lastEvidenceAt=clock.getAsLong();unchanged=0;}else unchanged++;
        evidence.put(id,Map.of("executionId",id,"tool",tool,"status",status,"newInformation",fresh));
        // 账本按 LRU 只保留最近 128 条证据。
        while(evidence.size()>128)evidence.remove(evidence.keySet().iterator().next());
        return fresh;
    }
    /** 根任务（scope=root）的进展评估入口。 */
    public synchronized Map<String,Object> review(List<String> acceptance,List<String> ids,String conclusion,String verification){
        return review("root",acceptance,ids,conclusion,verification);
    }
    /** 子任务 scope 也可评估；入参长度限制用于防止模型用超长自述绕过证据校验。 */
    public synchronized Map<String,Object> review(String scope,List<String> acceptance,List<String> ids,String conclusion,String verification){
        // 1. 结构校验：验收条件/证据编号必须非空且不超过 12 条，说明文字有长度上限。
        if(acceptance.isEmpty() || acceptance.size()>12 || acceptance.stream().anyMatch(s->s.isBlank() || s.length()>500)
                || ids.isEmpty() || ids.size()>12 || conclusion.isBlank() || conclusion.length()>2000 || verification.isBlank() || verification.length()>1000)
            return Map.of("success",false,"output","请给出验收条件、真实执行记录编号、判断依据和下一项验证；不能用自述代替证据");
        var existing=criteriaByScope.getOrDefault(scope,List.of());
        // 2. 验收条件冻结：同一 scope 首次提交后不得降低或替换，防止执行中放宽标准。
        if(!existing.isEmpty() && !existing.equals(acceptance))return Map.of("success",false,"output","验收条件已记录，不得在执行中降低或替换");
        // 3. scope 数量上限：子任务评估范围最多 128 个。
        if(!criteriaByScope.containsKey(scope) && criteriaByScope.size()>=128)return Map.of("success",false,"output","子任务评估范围已满，请汇总已有结果");
        // 4. 引用必须真实存在：编号必须来自本请求实际产生的证据。
        if(ids.stream().anyMatch(id->!evidence.containsKey(id)))return Map.of("success",false,"output","引用的证据不存在于当前请求中，不能据此判断进展");
        // 5. 必须至少引用一条"未评估过且带新信息"的证据；重复解释或已评估证据不能续期。
        if(ids.stream().noneMatch(id->!reviewed.contains(scope+"|"+id) && Boolean.TRUE.equals(evidence.get(id).get("newInformation"))))
            return Map.of("success",false,"output","这些记录没有未评估的新信息；重复解释不能续期，请检查停滞原因");
        // 6. 校验通过后落库：冻结验收条件、记录评估/引用的证据、下一项验证；仅作为"模型判断"保存。
        criteriaByScope.put(scope,List.copyOf(acceptance));if(scope.equals("root"))criteria=List.copyOf(acceptance);
        citations=List.copyOf(ids);ids.forEach(id->reviewed.add(scope+"|"+id));assessment=conclusion;nextCheck=verification;assessmentScope=scope;
        return Map.of("success",true,"output","证据引用已核对，解释作为模型判断保存；不代表已完成，也不会单独延长执行时间", "progress",snapshot());
    }
    public synchronized long lastEvidenceAt(){return lastEvidenceAt;}
    public synchronized int unchanged(){return unchanged;}
    /** 对外快照：只暴露最近 12 条证据，且 assessmentVerified 恒为 false —— 模型判断不等于事实验证。 */
    public synchronized Map<String,Object> snapshot(){
        var value=new LinkedHashMap<String,Object>();value.put("objective",objective);value.put("acceptanceCriteria",criteria);
        value.put("lastEvidenceAt",lastEvidenceAt);value.put("unchangedResults",unchanged);
        value.put("evidence",new ArrayList<>(evidence.values()).stream().skip(Math.max(0,evidence.size()-12)).toList());
        value.put("modelAssessment",assessment);value.put("assessmentEvidenceIds",citations);value.put("nextVerification",nextCheck);
        value.put("assessmentScope",assessmentScope);value.put("scopeAcceptanceCriteria",criteriaByScope.getOrDefault(assessmentScope,List.of()));
        value.put("assessmentVerified",false);return value;
    }
}
