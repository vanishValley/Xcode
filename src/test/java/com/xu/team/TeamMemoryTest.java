package com.xu.team;

import com.xu.llm.LlmClient;
import com.xu.memory.MemoryManager;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class TeamMemoryTest {
    @Test void transientMemoryCompactsThroughBudgetedClientWithoutPersistingOrdinarySession() throws Exception {
        TeamConfig config = TeamConfig.defaults();
        TeamBudget budget = new TeamBudget(config);
        AtomicInteger calls = new AtomicInteger(), outputLimit = new AtomicInteger();
        LlmClient delegate = new LlmClient("", "fake") {
            @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools, int cap) {
                calls.incrementAndGet(); outputLimit.set(cap);
                Message summary = new Message("assistant", "已调查接口，下一步核实跨域配置。");
                summary.inputTokens = 100; summary.outputTokens = 10; return summary;
            }
        };
        CancellationToken token = new CancellationToken();
        try (TeamRuntime runtime = new TeamRuntime(config, token, (rt, id, task, childToken) ->
                (input, hooks) -> null, null, null)) {
            TeamModelClient model = new TeamModelClient(delegate, budget, config, runtime, "lead", token);
            MemoryManager memory = MemoryManager.forTeam(null, model, "project");
            List<LlmClient.Message> history = new ArrayList<>();
            history.add(new LlmClient.Message("system", "rules"));
            for (int i = 0; i < 10; i++) {
                history.add(new LlmClient.Message("user", "investigation " + i));
                history.add(new LlmClient.Message("assistant", "e".repeat(20000)));
            }
            memory.compactIfNeeded(history);
            assertEquals(1, calls.get());
            assertTrue(history.size() < 21);
            assertEquals(config.outputTokens(), outputLimit.get());
            assertEquals(1, budget.snapshot().get("calls"));
            assertEquals(110L, budget.snapshot().get("usedTokens"));
            memory.persist(history);
            assertTrue(memory.loadSession().isEmpty());
        }
    }
}
