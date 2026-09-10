package com.xu.team;

import com.xu.agent.Agent;
import com.xu.agent.AgentRunHooks;

/** 使状态机能用可控假执行者测试，不依赖在线模型。 */
@FunctionalInterface
public interface TeamMember {
    Agent.RunResult run(String input, AgentRunHooks hooks) throws Exception;
}
