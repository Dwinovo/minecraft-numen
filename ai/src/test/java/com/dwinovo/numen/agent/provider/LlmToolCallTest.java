package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LlmToolCall} 的那一条契约:{@code arguments} 一定是合法的 JSON 对象文本。
 *
 * <p>为什么要在类型这一层保证,而不是让各个 provider 自己把关——这个字符串会进对话历史,
 * 历史每轮全量回传,还会落盘。一句截断的 JSON 混进去,之后每一次请求都带着它,上游每次
 * 都 400,而 400 不在重试白名单里、重启又原样读回。也就是说漏一次不是错一次,是这个
 * 同伴从此废掉,玩家自己还救不回来。
 */
class LlmToolCallTest {

    private static String argsOf(String raw) {
        return new LlmToolCall("id", "goto", raw).arguments();
    }

    // ---- 合法的原样保留 ----

    @Test
    void wellFormedArgumentsAreKeptByteForByte() {
        // 不重新序列化:replay 时线上字节要跟模型发的一致
        String raw = "{\"x\": 1, \"z\": -3}";
        assertEquals(raw, argsOf(raw));
    }

    @Test
    void nestedAndUnicodeArgumentsSurvive() {
        String raw = "{\"todos\":[{\"text\":\"挖 128 个铁\",\"done\":false}]}";
        assertEquals(raw, argsOf(raw));
    }

    // ---- 不合法的一律归一 ----

    @Test
    void aTruncatedObjectBecomesNoArgs() {
        // 这就是 #50 的原样:模型把参数当 XML 写进了 content,只剩半截 JSON
        assertEquals(LlmToolCall.NO_ARGS, argsOf("{\"x\": "));
    }

    @Test
    void blanksAndNullBecomeNoArgs() {
        assertEquals(LlmToolCall.NO_ARGS, argsOf(null));
        assertEquals(LlmToolCall.NO_ARGS, argsOf(""));
        assertEquals(LlmToolCall.NO_ARGS, argsOf("   "));
    }

    @Test
    void validJsonThatIsNotAnObjectBecomesNoArgs() {
        // 模型偶尔吐数组或裸值;它们能解析,但不是这个字段该有的形状
        assertEquals(LlmToolCall.NO_ARGS, argsOf("[1,2,3]"));
        assertEquals(LlmToolCall.NO_ARGS, argsOf("\"go north\""));
        assertEquals(LlmToolCall.NO_ARGS, argsOf("42"));
        assertEquals(LlmToolCall.NO_ARGS, argsOf("null"));
    }

    @Test
    void prosePassedOffAsArgumentsBecomesNoArgs() {
        assertEquals(LlmToolCall.NO_ARGS, argsOf("<tool>goto</tool><x>10</x>"));
        assertEquals(LlmToolCall.NO_ARGS, argsOf("好的,我这就过去"));
    }

    // ---- 契约对下游意味着什么 ----

    @Test
    void everyConstructedCallCanBeParsedAsAnObject() {
        // 下游(Anthropic 的 input、UI 的计划卡)据此可以直接解析,不必各自再防一道
        for (String raw : new String[]{null, "", "   ", "{\"x\":1}", "{\"x\": ", "[1]", "呃"}) {
            String args = argsOf(raw);
            JsonObject parsed = assertDoesNotThrow(
                    () -> JsonParser.parseString(args).getAsJsonObject(), "原文: " + raw);
            assertTrue(parsed != null, "原文: " + raw);
        }
    }

    @Test
    void theWireShapeNeverCarriesBrokenArguments() {
        // toOpenAIJson 的产物直接进 assistant 消息回传,这里漏了就是每轮都 400
        JsonObject fn = new LlmToolCall("call_1", "goto", "{\"x\": ")
                .toOpenAIJson().getAsJsonObject("function");
        assertEquals(LlmToolCall.NO_ARGS, fn.get("arguments").getAsString());
        assertDoesNotThrow(() -> JsonParser.parseString(fn.get("arguments").getAsString()));
    }

    @Test
    void readingBackAWireShapeIsAlsoCovered() {
        // 历史落盘再读回走的是这条路,毒药不能从磁盘绕回来
        JsonObject fn = new JsonObject();
        fn.addProperty("name", "goto");
        fn.addProperty("arguments", "{\"x\": ");
        JsonObject wire = new JsonObject();
        wire.addProperty("id", "call_1");
        wire.add("function", fn);

        assertEquals(LlmToolCall.NO_ARGS, LlmToolCall.fromOpenAIJson(wire).arguments());
    }

    @Test
    void idAndNameAreLeftAlone() {
        LlmToolCall call = new LlmToolCall("call_abc", "goto", "{\"x\": ");
        assertEquals("call_abc", call.id(), "id 要原样回传,否则上游对不上 tool_call_id");
        assertEquals("goto", call.name());
    }
}
