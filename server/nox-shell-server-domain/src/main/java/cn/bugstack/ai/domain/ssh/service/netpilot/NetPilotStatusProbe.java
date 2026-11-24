package cn.bugstack.ai.domain.ssh.service.netpilot;

import cn.bugstack.ai.domain.ssh.model.valobj.CommandExecutionResult;

public interface NetPilotStatusProbe {
    CommandExecutionResult readServiceStatus();
}
