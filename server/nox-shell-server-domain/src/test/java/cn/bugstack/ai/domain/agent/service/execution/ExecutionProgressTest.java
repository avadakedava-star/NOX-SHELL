package cn.bugstack.ai.domain.agent.service.execution;

import org.junit.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class ExecutionProgressTest {
    @Test public void freshEvidenceExtendsObservationButNeverChangesUltimateBudget(){
        var now=new AtomicLong(1000);var guard=new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),1100,now::get);
        long budget=guard.deadline();
        for(int n=0;n<10;n++){now.addAndGet(90);guard.observe("id"+n,"read","RETURNED","result"+n);guard.checkActive();}
        assertEquals(budget,guard.deadline());assertEquals("RUNNING",guard.progressState());
        now.addAndGet(110);guard.checkActive();assertEquals("CHECKING",guard.progressState());
        now.addAndGet(100);assertEquals("PROGRESS_REVIEW_REQUIRED",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void runningToolIsNotCancelledAtObservationDeadline(){
        var now=new AtomicLong(1000);var guard=new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),1100,now::get);
        guard.reserve("running");now.addAndGet(500);guard.checkActive();assertEquals("CHECKING",guard.progressState());
        now.set(guard.deadline());assertEquals("RESOURCE_BUDGET",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void modelCannotInventEvidenceLowerCriteriaOrRenewByRepeatingAssessment(){
        var now=new AtomicLong(1000);var progress=new ExecutionProgress(now::get);
        progress.objective("修复接口并验证");progress.objective("降低目标");
        assertEquals(false,progress.review(List.of("请求成功"),List.of("invented"),"完成了","再次请求").get("success"));
        progress.observe("real","read","RETURNED","result");long observed=progress.lastEvidenceAt();now.addAndGet(50);
        assertEquals(true,progress.review(List.of("请求成功"),List.of("real"),"已定位，尚未修复","修改后请求验证").get("success"));
        assertEquals(observed,progress.lastEvidenceAt());assertEquals(false,progress.snapshot().get("assessmentVerified"));
        assertEquals(false,progress.review(List.of("请求成功"),List.of("real"),"还在推进","再看一次").get("success"));
        progress.observe("second","read","RETURNED","new");
        assertEquals(false,progress.review(List.of("改过代码即可"),List.of("second"),"完成","无").get("success"));
        assertEquals("修复接口并验证",progress.snapshot().get("objective"));
    }
    @Test public void repeatedResultsTriggerPauseEvenWhenExecutionIdsChange(){
        var now=new AtomicLong(1000);var guard=new RequestExecutionGuard(ExecutionPolicyProperties.defaults(),1100,now::get);
        for(int n=0;n<9;n++)guard.observe("id"+n,"read","RETURNED","same-result");
        assertEquals("PROGRESS_STALLED",assertThrows(ExecutionHaltedException.class,guard::checkActive).reason());
    }
    @Test public void failedAndPendingResultsNeverBecomeCompletionProof(){
        var progress=new ExecutionProgress(()->1000L);
        progress.observe("failed","read","REPORTED_FAILURE","missing-file");
        assertEquals(true,progress.review(List.of("文件可读"),List.of("failed"),"确认路径错误，未完成","核查正确路径").get("success"));
        assertEquals(false,progress.snapshot().get("assessmentVerified"));
    }
    @Test public void subtaskCriteriaCannotReplaceRootAcceptanceAndNarrativeIsNotEvidence(){
        var progress=new ExecutionProgress(()->1000L);
        assertFalse(progress.observe("narrative","sshDiagnosisAgent","RETURNED","I made progress"));
        assertEquals(false,progress.review(List.of("接口可用"),List.of("narrative"),"完成了","无").get("success"));
        progress.observe("real","read","RETURNED","new output");
        assertEquals(true,progress.review("child",List.of("找到日志"),List.of("real"),"取得日志","分析原因").get("success"));
        assertEquals(List.of(),progress.snapshot().get("acceptanceCriteria"));
        assertEquals(true,progress.review(List.of("接口可用"),List.of("real"),"找到原因但未修复","修复后请求验证").get("success"));
        assertEquals(List.of("接口可用"),progress.snapshot().get("acceptanceCriteria"));
    }
}
