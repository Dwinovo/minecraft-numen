package com.dwinovo.numen.agent.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 技能正文的成型口。钉住它是因为有<b>两个</b>扳机会走这儿——模型自己 {@code load_skill},
 * 主人打斜杠命令——两边进上下文的东西必须一模一样。差异只在其中一条路上出问题,极难查。
 */
class SkillInjectionTest {

    private static SkillInfo skill(String name, String content) {
        return new SkillInfo(name, "desc", content, null);
    }

    @Test
    void bodyWrapsContentWithTheSkillNameAndHeading() {
        String out = SkillInjection.body(skill("build", "盖房子的步骤"), null);
        assertEquals("""
                <skill_content name="build">
                # Skill: build

                盖房子的步骤
                </skill_content>""", out);
    }

    @Test
    void ownerArgumentsRideAtTheTailOfTheSameBlock() {
        String out = SkillInjection.body(skill("build", "正文"), "在河边盖个木屋");
        assertTrue(out.endsWith("</skill_content>\nARGUMENTS: 在河边盖个木屋"),
                "主人的要求要缀在正文尾部、同一条里 —— 拆开的话模型读完正文容易忘了要什么");
    }

    @Test
    void blankArgumentsAddNothing() {
        assertFalse(SkillInjection.body(skill("build", "正文"), "   ").contains("ARGUMENTS"));
        assertFalse(SkillInjection.body(skill("build", "正文"), null).contains("ARGUMENTS"));
    }

    @Test
    void contentIsTrimmedSoStrayBlankLinesDoNotRideAlong() {
        String out = SkillInjection.body(skill("s", "\n\n  正文  \n\n"), null);
        assertTrue(out.contains("\n正文\n</skill_content>"), out);
    }

    @Test
    void quotesInNamesCannotBreakOutOfTheAttribute() {
        String out = SkillInjection.body(skill("a\"b<c&d", "x"), null);
        assertTrue(out.startsWith("<skill_content name=\"a&quot;b&lt;c&amp;d\">"), out);
    }

    @Test
    void supportFileCarriesBothSkillAndPath() {
        assertEquals("""
                <skill_file skill="build" path="references/roofs.md">
                屋顶怎么盖
                </skill_file>""",
                SkillInjection.supportFile("build", "references/roofs.md", "  屋顶怎么盖\n"));
    }
}
