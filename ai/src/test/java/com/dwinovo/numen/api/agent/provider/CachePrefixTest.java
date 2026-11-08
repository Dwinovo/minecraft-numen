package com.dwinovo.numen.api.agent.provider;

import com.dwinovo.numen.api.agent.catalog.ThinkingLevel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 前缀缓存靠请求前缀字节级稳定:同一段对话多走一步,工具表、系统提示、已有的消息都不能变样。
 * 另外三处发出去的缓存相关内容:Chat 转发到 Anthropic 型号时的显式断点、Responses 的 {@code prompt_cache_key}。
 */
class CachePrefixTest {

    private static IToolSpec tool(String name) {
        return new IToolSpec() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " tool"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of("type", "object"); }
        };
    }

    private static final List<IToolSpec> TOOLS = List.of(tool("look"), tool("mine"));

    private static LlmProvider provider(String kind) {
        return switch (kind) {
            case "chat" -> Wire.chat();
            case "responses" -> new OpenAIResponsesProvider(new ResponsesCompat(true));
            default -> new AnthropicProvider(new AnthropicCompat(false, true));
        };
    }

    private static JsonElement withoutCacheControl(JsonElement el) {
        JsonElement copy = el.deepCopy();
        strip(copy);
        return copy;
    }

    private static void strip(JsonElement el) {
        if (el.isJsonObject()) {
            el.getAsJsonObject().remove("cache_control");
            el.getAsJsonObject().entrySet().forEach(e -> strip(e.getValue()));
        } else if (el.isJsonArray()) {
            el.getAsJsonArray().forEach(CachePrefixTest::strip);
        }
    }

    private static JsonObject build(LlmProvider p, List<JsonObject> messages) {
        return p.buildRequestBody(Wire.reasoning("m", "[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}]"),
                "你是同伴", messages, p.buildToolList(TOOLS), new RequestOptions(null, 0, ThinkingLevel.HIGH), "s-1");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"chat", "responses", "anthropic"})
    void oneMoreStepLeavesToolsSystemAndEarlierMessagesUntouched(String kind) {
        LlmProvider p = provider(kind);
        List<JsonObject> history = new ArrayList<>(List.of(p.buildUserMessage("看看周围")));
        JsonObject before = build(p, history);

        AssistantTurn turn = new AssistantTurn("好", List.of(new LlmToolCall("call_1", "look", "{\"x\":1}")), null);
        history.addAll(p.assistantToRequestItems(turn));
        history.add(p.buildToolResultMessage("call_1", "{\"blocks\":3}"));
        history.add(p.buildUserMessage("继续"));
        JsonObject after = build(p, history);

        String messages = kind.equals("responses") ? "input" : "messages";
        JsonArray b = before.getAsJsonArray(messages);
        JsonArray a = after.getAsJsonArray(messages);
        for (int i = 0; i < b.size(); i++) {
            assertEquals(withoutCacheControl(b.get(i)), withoutCacheControl(a.get(i)), messages + "[" + i + "]");
        }
        assertEquals(withoutCacheControl(before.get("tools")), withoutCacheControl(after.get("tools")));
        if (before.has("system")) {
            assertEquals(withoutCacheControl(before.get("system")), withoutCacheControl(after.get("system")));
        }
    }

    // ---- Chat 转发到 Anthropic 型号 ----

    private static final OpenAIProvider ROUTED = new OpenAIProvider(new ChatCompat(
            ChatCompat.ThinkingFormat.OPENROUTER, ChatCompat.MaxTokensField.MAX_TOKENS, false, true, true));

    private static JsonObject routed(List<JsonObject> messages) {
        return ROUTED.buildRequestBody(Wire.PLAIN, "你是同伴", messages, ROUTED.buildToolList(TOOLS),
                RequestOptions.NONE, null);
    }

    private static boolean marked(JsonElement part) {
        return part.isJsonObject() && part.getAsJsonObject().has("cache_control");
    }

    private static JsonArray contentOf(JsonArray messages, int i) {
        return messages.get(i).getAsJsonObject().getAsJsonArray("content");
    }

    @Test
    void routedChatMarksTheSystemPromptTheLastToolAndTheTwoLastTextMessages() {
        List<JsonObject> messages = new ArrayList<>();
        messages.add(ROUTED.buildUserMessage("看看周围"));
        messages.addAll(ROUTED.assistantToRequestItems(new AssistantTurn("", List.of(
                new LlmToolCall("call_1", "look", "{}")), null)));
        messages.add(ROUTED.buildToolResultMessage("call_1", "{\"blocks\":3}"));
        messages.add(ROUTED.buildUserMessage("<runtime_state/>"));

        JsonObject body = routed(messages);

        JsonArray wire = body.getAsJsonArray("messages");
        assertEquals("system", wire.get(0).getAsJsonObject().get("role").getAsString());
        assertTrue(marked(contentOf(wire, 0).get(0)), "system");
        assertTrue(wire.get(1).getAsJsonObject().get("content").isJsonPrimitive(), "更早的历史不放断点");
        assertFalse(wire.get(2).getAsJsonObject().get("content").isJsonArray()
                        && marked(contentOf(wire, 2).get(0)),
                "只带工具调用、没有文字的助手消息跳过,不占断点");
        assertTrue(marked(contentOf(wire, 3).get(0)), "倒数第二块:历史末尾(工具结果)");
        assertTrue(marked(contentOf(wire, 4).get(0)), "最后一块:每轮现算的 runtime_state");
        JsonArray tools = body.getAsJsonArray("tools");
        assertFalse(tools.get(0).getAsJsonObject().has("cache_control"));
        assertEquals("ephemeral", tools.get(1).getAsJsonObject().getAsJsonObject("cache_control")
                .get("type").getAsString());
    }

    @Test
    void routedChatLeavesTheCallersMessagesAndToolsAlone() {
        List<JsonObject> messages = List.of(ROUTED.buildUserMessage("嗨"));
        JsonArray tools = ROUTED.buildToolList(TOOLS);
        ROUTED.buildRequestBody(Wire.PLAIN, "你是同伴", messages, tools, RequestOptions.NONE, null);
        assertTrue(messages.get(0).get("content").isJsonPrimitive());
        assertFalse(tools.get(1).getAsJsonObject().has("cache_control"));
    }

    @Test
    void plainChatSendsNoCacheControlAtAll() {
        JsonObject body = Wire.body(Wire.chat(), Wire.PLAIN, "你是同伴", List.of(Wire.chat().buildUserMessage("嗨")),
                RequestOptions.NONE);
        assertFalse(body.toString().contains("cache_control"));
    }

    // ---- Responses 的缓存键 ----

    @Test
    void responsesPassesTheConversationIdAsPromptCacheKey() {
        OpenAIResponsesProvider p = new OpenAIResponsesProvider(new ResponsesCompat(true));
        JsonObject body = p.buildRequestBody(Wire.PLAIN, "s", List.of(), new JsonArray(), RequestOptions.NONE,
                "0b9d7b5e-6a37-4b8e-8a43-6a3c7a1c2a11");
        assertEquals("0b9d7b5e-6a37-4b8e-8a43-6a3c7a1c2a11", body.get("prompt_cache_key").getAsString());
        assertFalse(p.buildRequestBody(Wire.PLAIN, "s", List.of(), new JsonArray(), RequestOptions.NONE, null)
                .has("prompt_cache_key"));
    }

    @Test
    void theCacheKeyIsCutToSixtyFourCharacters() {
        OpenAIResponsesProvider p = new OpenAIResponsesProvider(new ResponsesCompat(true));
        String key = p.buildRequestBody(Wire.PLAIN, "s", List.of(), new JsonArray(), RequestOptions.NONE,
                "x".repeat(100)).get("prompt_cache_key").getAsString();
        assertNotNull(key);
        assertEquals(64, key.length());
    }
}
