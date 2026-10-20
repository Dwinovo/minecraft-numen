package com.dwinovo.numen.mcp.client;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 借来的工具叫什么名字。
 *
 * <p>这里每一条守的都是同一件事:<b>名字不合规,被打回的是整个请求</b>。工具清单每轮都发,
 * 所以一个坏名字不是"这个工具用不了",是这个同伴从此每句话都失败。
 */
class McpToolNameTest {

    /** 上游认的形状。 */
    private static final Pattern LEGAL = Pattern.compile("[a-zA-Z0-9_-]{1,64}");

    private static String qualify(String server, String tool) {
        return McpToolName.qualify(server, tool, new HashSet<>());
    }

    private static void assertLegal(String name) {
        assertTrue(LEGAL.matcher(name).matches(), "上游不会收这个名字: '" + name + "'");
        assertTrue(name.length() <= McpToolName.MAX_LENGTH, "超长: " + name.length());
    }

    // ---- 字符 ----

    @Test
    void aDottedRemoteNameIsTheCommonCaseAndMustBeCleaned() {
        // browser.navigate / fs.read_file 这种在 MCP 那边很常见,而点是非法字符
        String name = qualify("mybrowser", "browser.navigate");
        assertEquals("mybrowser__browser_navigate", name);
        assertLegal(name);
    }

    @Test
    void bothHalvesAreCleanedNotJustTheServer() {
        // 只洗一半等于没洗:两半拼出来的整串才是发给上游的东西
        assertLegal(qualify("my.server", "read file"));
        assertLegal(qualify("MY SERVER", "TOOL@1"));
    }

    @Test
    void nonAsciiNamesStillComeOutLegal() {
        assertLegal(qualify("我的服务", "读取文件"));
        assertLegal(qualify("café", "naïve"));
    }

    @Test
    void anEmptyHalfDoesNotProduceALeadingSeparator() {
        // "" + "__" + tool 会拼出 __tool,看着像是我们自己的命名约定,其实是漏了服务名
        String name = qualify("", "tool");
        assertLegal(name);
        assertTrue(name.startsWith("_"), name);
        assertEquals("_" + McpToolName.SEPARATOR + "tool", name);
    }

    // ---- 长度 ----

    @Test
    void anOverlongNameIsCutToTheLimit() {
        assertLegal(qualify("s".repeat(40), "t".repeat(40)));
        assertLegal(qualify("s".repeat(200), "t"));
        assertLegal(qualify("s", "t".repeat(200)));
        assertLegal(qualify("s".repeat(200), "t".repeat(200)));
    }

    @Test
    void twoOverlongNamesSharingAPrefixStayDistinct() {
        // 光截断的话,前 64 字符相同的两个工具会变成同一个名字——那是静默丢一个能力
        Set<String> taken = new LinkedHashSet<>();
        String a = McpToolName.qualify("server", "very_long_tool_name_".repeat(5) + "alpha", taken);
        String b = McpToolName.qualify("server", "very_long_tool_name_".repeat(5) + "beta", taken);
        assertNotEquals(a, b);
        assertLegal(a);
        assertLegal(b);
    }

    // ---- 唯一 ----

    @Test
    void namesThatCollideOnlyAfterCleaningGetSeparated() {
        // my.server 和 my_server 洗完是同一个;注册表遇到重名是抛异常的,不能指望它兜底
        Set<String> taken = new HashSet<>();
        String a = McpToolName.qualify("my.server", "tool", taken);
        String b = McpToolName.qualify("my_server", "tool", taken);
        assertEquals("my_server__tool", a);
        assertNotEquals(a, b);
        assertLegal(b);
    }

    @Test
    void aThirdCollisionKeepsCounting() {
        Set<String> taken = new HashSet<>();
        String a = McpToolName.qualify("s", "t", taken);
        String b = McpToolName.qualify("s", "t", taken);
        String c = McpToolName.qualify("s", "t", taken);
        assertEquals(3, Set.of(a, b, c).size(), "三次都得是不同的名字");
    }

    @Test
    void theTakenSetIsFilledInSoCallersJustPassItAlong() {
        Set<String> taken = new HashSet<>();
        McpToolName.qualify("s", "t", taken);
        assertTrue(taken.contains("s__t"), "调用方按顺序传同一个集合就够,不用自己记账");
    }

    @Test
    void deduplicationSurvivesTheLengthLimit() {
        // 撞名要加后缀,而加后缀不能把名字顶出上限
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            assertLegal(McpToolName.qualify("s".repeat(60), "t".repeat(60), taken));
        }
        assertEquals(12, taken.size(), "十二个都得各不相同");
    }
}
