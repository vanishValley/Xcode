package com.xu.ui.tui;

import com.xu.skill.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Context-aware completion for slash commands and Skill names. */
final class CommandCompleter implements Completer {

    private static final Map<String, String> ROOT_COMMANDS =
            new LinkedHashMap<>();

    static {
        ROOT_COMMANDS.put("/help", "显示帮助");
        ROOT_COMMANDS.put("/status", "当前模型、项目与安全状态");
        ROOT_COMMANDS.put("/tools", "查看可用工具");
        ROOT_COMMANDS.put("/skills", "查看 Skills");
        ROOT_COMMANDS.put("/skill", "管理 Skill");
        ROOT_COMMANDS.put("/plan", "规划并执行复杂任务");
        ROOT_COMMANDS.put("/save", "保存长期记忆");
        ROOT_COMMANDS.put("/memory", "查看或清空长期记忆");
        ROOT_COMMANDS.put("/history", "管理本地输入历史");
        ROOT_COMMANDS.put("/hitl", "查看或切换危险操作审批");
        ROOT_COMMANDS.put("/clear", "开启新对话");
        ROOT_COMMANDS.put("/exit", "退出");
    }

    private final SkillRegistry skills;

    CommandCompleter(SkillRegistry skills) {
        this.skills = skills;
    }

    @Override
    public void complete(
            LineReader reader,
            ParsedLine line,
            List<Candidate> candidates) {
        List<String> words = line.words();
        if (words.isEmpty() || !words.get(0).startsWith("/")) {
            return;
        }

        /*
         * JLine replaces only ParsedLine.word(), not the whole input buffer.
         * Candidates therefore contain one token at a time. Supplying
         * "/skill on foo" while completing the third token would duplicate
         * the prefix and produce "/skill on /skill on foo".
         */
        if (line.wordIndex() == 0) {
            ROOT_COMMANDS.forEach((command, description) -> candidates.add(
                    candidate(command, "命令", description)));
            return;
        }

        String command = words.get(0);
        if ("/skill".equals(command) && line.wordIndex() == 1) {
            addTokens(
                    candidates,
                    "Skill",
                    Map.of(
                            "reload", "重新扫描 Skills",
                            "on", "启用 Skill",
                            "off", "禁用 Skill"));
            return;
        }
        if ("/skill".equals(command)
                && line.wordIndex() == 2
                && words.size() >= 2
                && ("on".equals(words.get(1))
                || "off".equals(words.get(1)))) {
            skills.allSkills().forEach(skill -> candidates.add(new Candidate(
                    skill.name(),
                    skill.name(),
                    "Skills",
                    skill.description(),
                    null,
                    null,
                    true)));
            return;
        }

        if (line.wordIndex() != 1) {
            return;
        }
        switch (command) {
            case "/hitl" -> addTokens(
                    candidates,
                    "HITL",
                    Map.of(
                            "on", "启用危险操作审批",
                            "off", "关闭危险操作审批"));
            case "/memory" -> addTokens(
                    candidates,
                    "记忆",
                    Map.of("clear", "清空长期记忆"));
            case "/history" -> addTokens(
                    candidates,
                    "历史",
                    Map.of("clear", "清空 TUI 输入历史"));
            case "/save" -> addTokens(
                    candidates,
                    "记忆",
                    Map.of("-g", "保存为全局记忆"));
            default -> {
                // Free-form arguments such as /plan <task> are not completed.
            }
        }
    }

    private static void addTokens(
            List<Candidate> candidates,
            String group,
            Map<String, String> tokens) {
        tokens.forEach((token, description) -> candidates.add(
                candidate(token, group, description)));
    }

    private static Candidate candidate(
            String value,
            String group,
            String description) {
        return new Candidate(
                value,
                value,
                group,
                description,
                null,
                null,
                true);
    }
}
