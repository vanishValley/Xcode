package com.xu.agent;

import com.xu.cli.CommandProcessor;
import com.xu.llm.LlmClient;
import com.xu.memory.MemoryManager;
import com.xu.observability.Tracing;
import com.xu.team.TeamCoordinator;
import com.xu.tool.ToolRegistry;
import com.xu.ui.UiEventSink;
import com.xu.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentTeamCommandTest {
    @TempDir Path directory;
    @Test void commandIsExactAndReportReturnsToNormalHistoryAsConversationNotSystem() {
        LlmClient model = new LlmClient("", "fake") {
            @Override public Message chatRaw(List<Message> messages, List<Map<String, Object>> tools) {
                return new Message("assistant", "调查已完成，依据是给定资料，不需要代码修改。");
            }
        };
        ToolRegistry tools = new ToolRegistry();
        Agent ordinary = new Agent(model, tools, new MemoryManager(), "main");
        CommandProcessor commands = new CommandProcessor(ordinary, null, null, null, null, null, null,
                "fake", directory, directory.resolve("app.log"));
        commands.setTeamCoordinator(new TeamCoordinator(model, tools, null, null, directory, directory.resolve("data"),
                Tracing.noop(), UiEventSink.noop(), new CancellationToken()));
        assertEquals(CommandProcessor.Kind.INFO, commands.execute("/team").kind());
        assertEquals(CommandProcessor.Kind.WARNING, commands.execute("/teammate investigate").kind());
        CommandProcessor.Result result = commands.execute("/team 调查资料");
        assertEquals(CommandProcessor.Kind.ASSISTANT, result.kind());
        assertTrue(result.text().contains("SUCCESS"), result.text());
        List<LlmClient.Message> history = ordinary.getHistory();
        assertEquals(3, history.size());
        assertEquals("user", history.get(1).role); assertEquals("/team 调查资料", history.get(1).content);
        assertEquals("assistant", history.get(2).role);
    }
}
