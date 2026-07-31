package com.xu.ui.tui;

import com.xu.config.XcodePaths;
import com.xu.skill.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.ParsedLine;
import org.jline.reader.impl.DefaultParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandCompleterTest {

    @TempDir
    Path projectRoot;

    @Test
    void shouldCompleteOneTokenAtATime() {
        SkillRegistry skills =
                new SkillRegistry(new XcodePaths(projectRoot));
        skills.reload();
        CommandCompleter completer = new CommandCompleter(skills);

        List<Candidate> root = complete(completer, "/sk");
        assertTrue(root.stream()
                .anyMatch(candidate -> "/skill".equals(
                        candidate.value())));

        List<Candidate> action = complete(completer, "/skill o");
        assertTrue(action.stream()
                .anyMatch(candidate -> "on".equals(candidate.value())));
        assertTrue(action.stream()
                .anyMatch(candidate -> "off".equals(candidate.value())));

        List<Candidate> names =
                complete(completer, "/skill on ");
        assertTrue(names.stream()
                .anyMatch(candidate -> "web-access".equals(
                        candidate.value())));
        assertFalse(names.stream()
                .anyMatch(candidate -> candidate.value()
                        .startsWith("/skill on")));

        List<Candidate> history =
                complete(completer, "/history ");
        assertTrue(history.stream()
                .anyMatch(candidate -> "clear".equals(
                        candidate.value())));
    }

    private static List<Candidate> complete(
            CommandCompleter completer,
            String line) {
        ParsedLine parsed =
                new DefaultParser().parse(line, line.length());
        List<Candidate> candidates = new ArrayList<>();
        completer.complete(null, parsed, candidates);
        return candidates;
    }
}
