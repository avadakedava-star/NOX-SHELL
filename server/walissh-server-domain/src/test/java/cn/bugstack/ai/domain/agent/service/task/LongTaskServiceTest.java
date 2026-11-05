package cn.bugstack.ai.domain.agent.service.task;
import cn.bugstack.ai.domain.agent.adapter.repository.*;
import cn.bugstack.ai.domain.agent.adapter.port.ILongTaskRunner;
import cn.bugstack.ai.domain.agent.service.audit.*;
import cn.bugstack.ai.domain.agent.service.execution.*;
import cn.bugstack.ai.domain.ssh.service.*;
import cn.bugstack.ai.domain.ssh.model.entity.*;
import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LongTaskServiceTest {
    static class Memory implements IAgentTaskRepository {
        final ObjectMapper mapper=new ObjectMapper();final Map<String,String> tasks=new HashMap<>();final Map<String,List<String>> steps=new HashMap<>();final Set<String> cancelled=new HashSet<>();
        String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new RuntimeException(e);}}
        <T>T parse(String v,Class<T> type){try{return mapper.readValue(v,type);}catch(Exception e){throw new RuntimeException(e);}}
        public void insert(AgentTask t,List<AgentTaskStep>s){save(t);steps.put(t.id,s.stream().map(this::json).toList());}
        public AgentTask find(String id){if(!tasks.containsKey(id))return null;var t=parse(tasks.get(id),AgentTask.class);t.cancelRequested=cancelled.contains(id);return t;}
        public List<AgentTask> list(String u,String s){return tasks.keySet().stream().map(this::find).filter(t->t.userId.equals(u)&&t.sessionId.equals(s)).toList();}
        public List<String> runnable(){return tasks.keySet().stream().filter(id->!find(id).terminal()).toList();}
        public void save(AgentTask t){tasks.put(t.id,json(t));}
        public List<AgentTaskStep> steps(String id){return steps.get(id).stream().map(s->parse(s,AgentTaskStep.class)).toList();}
        public void saveStep(AgentTaskStep s){steps.put(s.taskId,steps.get(s.taskId).stream().map(v->parse(v,AgentTaskStep.class).key.equals(s.key)?json(s):v).toList());}
        public boolean claim(String id,String worker,long now,long until){return true;}
        public void release(String id,String worker){}
        public void requestCancel(String id){cancelled.add(id);}
    }
    static class Remote implements ILongTaskRunner {
        final Map<String,Job> jobs=new HashMap<>();final Map<String,Integer> starts=new HashMap<>();boolean image,container,ready,loseCreateReply;String actualImage="mysql:8.0";int probes;
        public CommandExecutionResult query(AgentTask t,String c,long timeout){
            if(c.contains("docker image inspect"))return image?ok("image"):CommandExecutionResult.completed(1,"","No such image");
            if(c.contains("docker inspect")){
                if(!container)return CommandExecutionResult.completed(1,"","No such object");
                var spec=t.container;var root=new LinkedHashMap<String,Object>();root.put("Id","container-id");
                root.put("Config",Map.of("Image",actualImage,"Env",spec.environment().entrySet().stream().map(e->e.getKey()+"="+e.getValue()).toList(),"Cmd",spec.arguments()));
                root.put("HostConfig",Map.of("PortBindings",Map.of(spec.containerPort()+"/tcp",List.of(Map.of("HostIp",spec.bindAddress(),"HostPort",String.valueOf(spec.hostPort())))),"Binds",spec.volumes()));
                root.put("State",Map.of("Status","running","Running",true));try{return ok(new ObjectMapper().writeValueAsString(List.of(root)));}catch(Exception e){throw new RuntimeException(e);}
            }
            if(c.contains("docker exec")){probes++;return ready?ok("alive"):CommandExecutionResult.completed(1,"","not ready yet");}
            return ok("");
        }
        CommandExecutionResult ok(String s){return CommandExecutionResult.completed(0,s,"");}
        public Job start(AgentTask t,String id,String c,int seconds){starts.merge(id,1,Integer::sum);Job result;
            if(c.contains("docker pull")){image=true;result=new Job("EXIT",0,"pulled");}
            else if(c.contains("docker run")){container=true;result=new Job("EXIT",0,"container-id");}
            else result=new Job("RUNNING",null,"running");
            jobs.put(id,result);return loseCreateReply&&c.contains("docker run")?new Job("UNREACHABLE",null,""):result;
        }
        public Job poll(AgentTask t,String id){return jobs.getOrDefault(id,new Job("MISSING",null,""));}
        public Job cancel(AgentTask t,String id){var result=new Job("EXIT",143,"terminated");jobs.put(id,result);return result;}
        public void close(String id){}
    }
    final Memory repository=new Memory();final Remote remote=new Remote();final AtomicLong now=new AtomicLong(System.currentTimeMillis());
    final ISshTerminalService terminals=mock(ISshTerminalService.class);final ISshConnectionService connections=mock(ISshConnectionService.class);
    final ToolExecutionAudit audit=new ToolExecutionAudit(mock(IToolExecutionRepository.class));final List<LongTaskService> services=new ArrayList<>();
    final TaskPolicy policy=new TaskPolicy(600,600,180,1200);
    @Before public void setup(){when(terminals.getTerminalSession("terminal")).thenReturn(TerminalSessionEntity.builder().sessionId("terminal").status(1).connectionId("server").build());when(connections.getConnection("server")).thenReturn(SshConnectionEntity.builder().connectionId("server").userId("user").host("host").port(22).username("ubuntu").build());}
    LongTaskService service(){var s=new LongTaskService(repository,remote,terminals,connections,policy,audit,new ContainerReadinessChecker(remote),now::get);services.add(s);return s;}
    ToolAuditContext context(){return new ToolAuditContext("request","user","chat","invocation","agent","terminal",null,null);}
    AgentTask.ContainerSpec spec(){return new AgentTask.ContainerSpec("mysql:8.0","mysql8","127.0.0.1",3306,3306,Map.of("MYSQL_ROOT_PASSWORD","test-secret"),List.of(),List.of("mysql-data:/var/lib/mysql"),"mysqladmin ping",180);}
    @After public void cleanup(){services.forEach(LongTaskService::shutdown);}
    @Test public void deploymentWaitsForConnectionWithoutModelPolling(){
        var s=service();var task=s.submitContainer(context(),spec());for(int i=0;i<5;i++)s.tick(task.id);
        assertEquals("WAITING",repository.find(task.id).status);assertEquals("container-id",repository.find(task.id).resourceId);
        now.addAndGet(35000);s.tick(task.id);assertEquals("WAITING",repository.find(task.id).status);
        remote.ready=true;s.tick(task.id);assertEquals("SUCCEEDED",repository.find(task.id).status);assertEquals(1,(int)remote.starts.get("CREATE-1"));
        assertFalse(s.detail(task.id,"user","chat").toString().contains("test-secret"));
    }
    @Test public void longCommandSurvivesChatEndAndServiceRestartWithoutResending(){
        var s=service();var context=context();var task=s.submitCommand(context,"构建","make package",0);s.tick(task.id);context.guard().halt("REQUEST_FINISHED","聊天已结束");
        now.addAndGet(181000);var restarted=service();restarted.tick(task.id);
        assertEquals("RUNNING",repository.find(task.id).status);assertEquals(1,(int)remote.starts.get("COMMAND-1"));
        remote.jobs.put("COMMAND-1",new ILongTaskRunner.Job("EXIT",0,"done"));restarted.tick(task.id);assertEquals("SUCCEEDED",repository.find(task.id).status);
    }
    @Test public void lostCreateReplyReconcilesExistingContainerBeforeAnyRetry(){
        var s=service();remote.loseCreateReply=true;var task=s.submitContainer(context(),spec());for(int i=0;i<3;i++)s.tick(task.id);
        assertTrue(remote.container);var restarted=service();restarted.tick(task.id);assertEquals("READY",repository.find(task.id).stage);assertEquals(1,(int)remote.starts.get("CREATE-1"));
        remote.ready=true;restarted.tick(task.id);assertEquals("SUCCEEDED",repository.find(task.id).status);
    }
    @Test public void cancelIsSeparateFromChatAndDoesNotDeleteCreatedContainer(){
        var s=service();var task=s.submitContainer(context(),spec());for(int i=0;i<4;i++)s.tick(task.id);s.cancel(task.id,"user","chat");s.tick(task.id);
        assertEquals("CANCELLED",repository.find(task.id).status);assertTrue(remote.container);assertEquals(0,remote.probes);
    }
    @Test public void startupDeadlineStopsChecksWithoutRecreatingContainer(){
        var s=service();var task=s.submitContainer(context(),spec());for(int i=0;i<5;i++)s.tick(task.id);now.addAndGet(181000);s.tick(task.id);
        assertEquals("FAILED",repository.find(task.id).status);assertTrue(remote.container);assertEquals(1,(int)remote.starts.get("CREATE-1"));
    }
    @Test public void duplicateSubmissionReturnsSameTaskAndOtherUsersCannotControlIt(){
        var s=service();var first=s.submitContainer(context(),spec());var second=s.submitContainer(context(),spec());assertEquals(first.id,second.id);
        assertThrows(IllegalArgumentException.class,()->s.cancel(first.id,"other","chat"));assertFalse(repository.find(first.id).cancelRequested);
    }
    @Test public void conflictingExistingResourceIsNotOverwritten(){
        var s=service();remote.container=true;remote.actualImage="postgres:17";var task=s.submitContainer(context(),spec());s.tick(task.id);
        assertEquals("FAILED",repository.find(task.id).status);assertTrue(remote.starts.isEmpty());
    }
    @Test public void blacklistAlsoAppliesToGenericLongCommands(){
        assertThrows(IllegalArgumentException.class,()->service().submitCommand(context(),"删除","rm -rf /tmp/x",600));assertTrue(repository.tasks.isEmpty());
    }
    @Test public void missingRemoteJournalAfterRestartNeverReplaysAWrite(){
        var s=service();var task=s.submitCommand(context(),"安装","apt-get install -y demo",600);s.tick(task.id);
        remote.jobs.clear();service().tick(task.id);assertEquals("UNKNOWN",repository.find(task.id).status);assertEquals(1,(int)remote.starts.get("COMMAND-1"));
    }
    @Test public void currentLocalUiIdentityCanUseItsExistingConnection(){
        when(connections.getConnection("server")).thenReturn(SshConnectionEntity.builder().connectionId("server").userId("default").host("host").port(22).username("ubuntu").build());
        var local=new ToolAuditContext("request","walissh-user","chat","invocation","agent","terminal",null,null);
        assertEquals("PENDING",service().submitCommand(local,"查询","printf ok",60).status);
        assertThrows(IllegalArgumentException.class,()->service().submitCommand(context(),"查询","printf ok",60));
    }
}
