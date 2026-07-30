package com.xu.skill;

import com.xu.config.XcodePaths;
import com.xu.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SkillRegistryTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldLoadBuiltinWebAccessSkillFromClasspathManifest() {
        SkillRegistry skills = new SkillRegistry(new XcodePaths(projectRoot));
        skills.reload();

        Skill webAccess = skills.findSkill("web-access").orElseThrow();
        assertEquals(Skill.Source.BUILTIN, webAccess.source());
        assertTrue(webAccess.description().contains("联网搜索"));
        assertTrue(webAccess.body().contains("web_search"));
        assertTrue(skills.warnings().isEmpty(),
                String.join("; ", skills.warnings()));
    }

    @Test
    void loadSkillShouldReturnBodyInCurrentToolResult() throws Exception {
        SkillRegistry skills = new SkillRegistry(new XcodePaths(projectRoot));
        skills.reload();
        ToolRegistry tools = new ToolRegistry();
        tools.registerLoadSkillTool(skills);

        String result = tools.get("load_skill")
                .execute(Map.of("name", "web-access"));

        assertTrue(result.contains("已加载 Skill: web-access"));
        assertTrue(result.contains("工具路由"));
    }
}
