package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.skill.SkillRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 装技能的工具,从模型的入口调:技能正文原样是这次调用的结果;点了一个没有的技能,结果说没有这一份并列出能用的那些——主人在设置里
 * 关掉的不列,和 {@code <available_skills>} 索引一样。
 */
class SkillToolTest {

    @TempDir
    static Path home;

    @BeforeAll
    static void install() throws IOException {
        Path skill = Files.createDirectories(home.resolve("skills").resolve("lumber"));
        Files.writeString(skill.resolve(SkillRegistry.SKILL_FILENAME), """
                ---
                name: lumber
                description: Fell a tree and take every log.
                ---

                # Felling trees

                Start from the lowest log.
                """);
        Path off = Files.createDirectories(home.resolve("skills").resolve("masonry"));
        Files.writeString(off.resolve(SkillRegistry.SKILL_FILENAME), """
                ---
                name: masonry
                description: Lay stone walls.
                ---

                # Walls
                """);
        SkillRegistry.instance().scan(home.resolve("skills"));
        SkillRegistry.instance().setEnabled("masonry", false);
    }

    @AfterAll
    static void uninstall() {
        SkillRegistry.instance().scan(null);
    }

    private static String call(String args) {
        AtomicReference<String> result = new AtomicReference<>();
        new SkillTool().invoke(new com.dwinovo.numen.agent.tool.ToolCall("t1", SkillTool.NAME, args, null,
                result::set));
        return result.get();
    }

    @Test
    void theSkillsTextIsTheResult() {
        assertEquals("""
                <skill_content name="lumber">
                # Felling trees

                Start from the lowest log.
                </skill_content>""", call("{\"skill\": \"lumber\"}"));
    }

    @Test
    void anUnknownSkillSaysSoAndNamesTheSkillsSheCanUse() {
        String result = call("{\"skill\": \"no_such_skill\"}");
        assertTrue(result.contains("\"success\":false"), result);
        assertTrue(result.contains("unknown skill: no_such_skill; the skills are: lumber\""), result);
        assertTrue(SkillRegistry.instance().formatXml().contains("<name>lumber</name>")
                && !SkillRegistry.instance().formatXml().contains("masonry"), SkillRegistry.instance().formatXml());
    }
}
