package cn.bugstack.ai.infrastructure.adapter.repository;

import cn.bugstack.ai.domain.agent.adapter.repository.ICommandApprovalRepository;
import cn.bugstack.ai.domain.agent.service.approval.CommandApproval;
import cn.bugstack.ai.infrastructure.dao.ICommandApprovalDao;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public class CommandApprovalRepository implements ICommandApprovalRepository {
    private final ICommandApprovalDao dao;
    private final ObjectMapper mapper=new ObjectMapper();
    public CommandApprovalRepository(ICommandApprovalDao dao) { this.dao=dao; }
    @PostConstruct public void initialize() { dao.createTable(); }
    private String encode(CommandApproval value) { try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("审批保存失败",e);} }
    private CommandApproval decode(String value) { if(value==null)return null;try{return mapper.readValue(value,CommandApproval.class);}catch(Exception e){throw new IllegalStateException("审批读取失败",e);} }
    public void insert(CommandApproval a) {dao.insert(a.id(),a.userId(),a.sessionId(),a.status(),a.expiresAt(),encode(a));}
    public CommandApproval find(String id) {return decode(dao.find(id));}
    public List<CommandApproval> findBySession(String user,String session) {return dao.list(user,session).stream().map(this::decode).toList();}
    public boolean transition(CommandApproval a,String expected,long now) {return dao.transition(a.id(),expected,a.status(),encode(a),now)==1;}
}
