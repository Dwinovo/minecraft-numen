package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.agent.provider.IToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 渐进披露:展开块渲染、从对话推导展开状态、目录生成。 */
class ToolDisclosureTest {

    private record Spec(String name, String description) implements IToolSpec {
        @Override public Map<String, Object> parameterSchema() {
            return Map.of("type", "object");
        }
    }

    private static ConvoState.Msg toolMsg(String content) {
        return new ConvoState.Msg.Tool("call_1", content);
    }

    // ---- 渲染 → 解析,一个来回 ----

    @Test
    void whatWeRenderIsWhatWeReadBack() {
        String block = ToolDisclosure.render(List.of(
                new Spec("craft", "Craft an item."),
                new Spec("lookup_recipe", "Look up how to make an item.")));
        assertEquals(Set.of("craft", "lookup_recipe"),
                ToolDisclosure.expandedIn(List.of(toolMsg(block))));
    }

    @Test
    void renderedBlockCarriesNameDescriptionAndSchema() {
        String block = ToolDisclosure.render(List.of(new Spec("craft", "Craft an item.")));
        assertTrue(block.contains("\"name\":\"craft\""), block);
        assertTrue(block.contains("\"description\":\"Craft an item.\""), block);
        assertTrue(block.contains("\"parameters\""), block);
    }

    // ---- 推导 ----

    @Test
    void emptyConversationExpandsNothing() {
        assertTrue(ToolDisclosure.expandedIn(List.of()).isEmpty());
        assertTrue(ToolDisclosure.expandedIn(null).isEmpty());
    }

    @Test
    void severalBlocksAcrossSeveralMessagesAllCount() {
        String a = ToolDisclosure.render(List.of(new Spec("craft", "x.")));
        String b = ToolDisclosure.render(List.of(new Spec("mine", "y.")));
        assertEquals(Set.of("craft", "mine"),
                ToolDisclosure.expandedIn(List.of(toolMsg(a), toolMsg("无关内容"), toolMsg(b))));
    }

    @Test
    void twoBlocksInOneMessageBothCount() {
        String a = ToolDisclosure.render(List.of(new Spec("craft", "x.")));
        String b = ToolDisclosure.render(List.of(new Spec("mine", "y.")));
        assertEquals(Set.of("craft", "mine"),
                ToolDisclosure.expandedIn(List.of(toolMsg(a + "\n" + b))));
    }

    @Test
    void droppingTheBlockRelocksTheTool() {
        // 压缩把那条工具结果总结掉了 —— 模型手里也没有 schema 了,闸就该重新拦住
        String block = ToolDisclosure.render(List.of(new Spec("craft", "x.")));
        assertTrue(ToolDisclosure.expandedIn(List.of(toolMsg(block))).contains("craft"));
        assertFalse(ToolDisclosure.expandedIn(List.of(toolMsg("(摘要:她查过合成表)")))
                .contains("craft"));
    }

    @Test
    void malformedMarkerIsIgnoredNotCrashed() {
        assertTrue(ToolDisclosure.expandedIn(List.of(toolMsg("<functions expanded=\"craft")))
                .isEmpty());
    }

    @Test
    void onlyToolResultsCountAsEvidence() {
        // 主人把展开块粘进聊天,或者模型自己复述了一遍 —— 都不算她真取回过定义
        String block = ToolDisclosure.render(List.of(new Spec("craft", "x.")));
        assertTrue(ToolDisclosure.expandedIn(
                List.of(new ConvoState.Msg.User(block))).isEmpty());
    }

    // ---- 目录 ----

    @Test
    void catalogIsOneLinePerTool() {
        String cat = ToolDisclosure.catalog(List.of(
                new Spec("craft", "Craft an item from materials. Second sentence ignored."),
                new Spec("mine", "Gather blocks by type and count.")));
        assertTrue(cat.startsWith("<deferred_tools>"), cat);
        assertTrue(cat.endsWith("</deferred_tools>"), cat);
        assertTrue(cat.contains("craft — Craft an item from materials."), cat);
        assertFalse(cat.contains("Second sentence"), cat);
    }

    @Test
    void catalogIsByteStableAcrossCalls() {
        // 目录进系统提示,两次生成不一致就等于每轮换前缀,前缀缓存直接作废
        List<Spec> tools = List.of(new Spec("a", "One."), new Spec("b", "Two."));
        assertEquals(ToolDisclosure.catalog(tools), ToolDisclosure.catalog(tools));
    }

    @Test
    void emptyCatalogIsEmptyString() {
        assertEquals("", ToolDisclosure.catalog(List.of()));
    }

    // ---- 摘要 ----

    @Test
    void summaryTakesTheFirstSentence() {
        assertEquals("Craft an item.",
                ToolDisclosure.summaryOf(new Spec("craft", "Craft an item. Then more prose.")));
    }

    @Test
    void summaryHandlesChinesePunctuation() {
        assertEquals("合成一件物品。",
                ToolDisclosure.summaryOf(new Spec("craft", "合成一件物品。材料从背包取。")));
    }

    @Test
    void summaryCollapsesNewlines() {
        assertEquals("Craft an item",
                ToolDisclosure.summaryOf(new Spec("craft", "Craft\n   an\titem")));
    }

    @Test
    void overlongSummaryIsTruncated() {
        String s = ToolDisclosure.summaryOf(new Spec("x", "a".repeat(300)));
        assertEquals(ToolDisclosure.SUMMARY_MAX, s.length());
        assertTrue(s.endsWith("…"), s);
    }

    // ---- 报错文案 ----

    @Test
    void notExpandedTellsHerHowToRecover() {
        String msg = ToolDisclosure.notExpanded("craft");
        assertTrue(msg.contains("craft"), msg);
        assertTrue(msg.contains("find_tools"), msg);
    }
}
