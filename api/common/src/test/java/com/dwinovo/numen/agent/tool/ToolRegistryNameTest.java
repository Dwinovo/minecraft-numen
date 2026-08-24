package com.dwinovo.numen.agent.tool;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 注册表对工具名的把关。
 *
 * <p>为什么这道闸要立在注册表而不是各个来源:工具清单<b>每一轮都随请求发出去</b>。一个上游
 * 不认的名字混进去,换来的不是"这个工具用不了",是整轮 400——同伴从此每句话都失败。名字来源
 * 会越来越多(自家的、MCP 借的、以后别的),在这里拦一次,比指望每个来源都记得洗要牢靠。
 */
class ToolRegistryNameTest {

    /** 一个只有名字的工具——这些用例只关心名字这道闸。 */
    private record Fake(String name) implements NumenTool {
        @Override public String description() { return "test"; }
        @Override public Map<String, Object> parameterSchema() { return Map.of(); }
        @Override public void invoke(ToolCall call) {
            throw new UnsupportedOperationException("不会被调到");
        }
    }

    private void removeQuietly(String name) {
        try {
            ToolRegistry.remove(name);
        } catch (RuntimeException ignored) {
            // 没注册上就没什么好清的
        }
    }

    @AfterEach
    void cleanUp() {
        for (String n : new String[]{"ok_name", "ok-name-2", "dup_name", "a".repeat(64)}) {
            removeQuietly(n);
        }
    }

    @Test
    void legalNamesGoIn() {
        ToolRegistry.register(new Fake("ok_name"));
        ToolRegistry.register(new Fake("ok-name-2"));
        assertNotNull(ToolRegistry.get("ok_name"));
        assertNotNull(ToolRegistry.get("ok-name-2"));
    }

    @Test
    void aNameAtTheLimitIsStillFine() {
        ToolRegistry.register(new Fake("a".repeat(64)));
        assertNotNull(ToolRegistry.get("a".repeat(64)));
    }

    @Test
    void illegalCharactersAreRefusedAtTheDoor() {
        // 点号是 MCP 那边最常见的:browser.navigate
        for (String bad : new String[]{"has.dot", "has space", "中文", "emoji_🙂", "has/slash", ""}) {
            assertThrows(IllegalArgumentException.class,
                    () -> ToolRegistry.register(new Fake(bad)), "这个名字应该被拒: '" + bad + "'");
            assertNull(ToolRegistry.get(bad), "被拒了就不该留在表里: '" + bad + "'");
        }
    }

    @Test
    void anOverlongNameIsRefused() {
        String tooLong = "a".repeat(65);
        assertThrows(IllegalArgumentException.class, () -> ToolRegistry.register(new Fake(tooLong)));
        assertNull(ToolRegistry.get(tooLong));
    }

    @Test
    void duplicatesStillThrowSoWiringBugsDoNotHideBehindTheNameCheck() {
        ToolRegistry.register(new Fake("dup_name"));
        assertThrows(IllegalStateException.class, () -> ToolRegistry.register(new Fake("dup_name")));
    }

    @Test
    void everyBuiltInToolAlreadySatisfiesTheRule() {
        // 自家工具在初始化时就会撞上这道闸;这条把"以后新加的也得守规矩"钉住
        for (String name : ToolRegistry.all().stream().map(NumenTool::name).toList()) {
            assertEquals(name, name.replaceAll("[^a-zA-Z0-9_-]", "_"), "自家工具名不合规: " + name);
        }
    }
}
