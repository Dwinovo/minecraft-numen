package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼实时语音合成的请求形状。
 *
 * <p>锁两件容易错的事:<b>合成要用专用模型</b>(拿实时对话模型会把要念的文本当提问回答),
 * 以及<b>手动提交要配 {@code mode: commit}</b>({@code server_commit} 是让服务端自己断句的
 * 另一种用法,和手动 commit 不是一回事)。
 */
class DashScopeTtsTest {

    private static JsonObject at(List<String> events, int index) {
        return JsonParser.parseString(events.get(index)).getAsJsonObject();
    }

    @Test
    void theFourEventsGoOutInTheOrderTheProtocolExpects() {
        List<String> events = DashScopeTts.requestEvents("Cherry", "你好");
        assertEquals(4, events.size());
        assertEquals("session.update", at(events, 0).get("type").getAsString());
        assertEquals("input_text_buffer.append", at(events, 1).get("type").getAsString());
        assertEquals("input_text_buffer.commit", at(events, 2).get("type").getAsString());
        assertEquals("session.finish", at(events, 3).get("type").getAsString(),
                "不说一声结束,服务端不知道后面还有没有文本");
    }

    @Test
    void theSessionUsesCommitModeBecauseWeSubmitTheTextOurselves() {
        JsonObject session = at(DashScopeTts.requestEvents("Cherry", "你好"), 0)
                .getAsJsonObject("session");
        assertEquals("commit", session.get("mode").getAsString());
        assertEquals("Cherry", session.get("voice").getAsString());
        assertEquals("pcm", session.get("response_format").getAsString());
        assertEquals(DashScopeTts.SAMPLE_RATE, session.get("sample_rate").getAsInt());
    }

    @Test
    void theTextGoesOutVerbatim() {
        assertEquals("挖 128 个铁,谢谢",
                at(DashScopeTts.requestEvents("Cherry", "挖 128 个铁,谢谢"), 1).get("text").getAsString());
    }

    @Test
    void everyEventCarriesItsOwnId() {
        List<String> events = DashScopeTts.requestEvents("Cherry", "你好");
        String first = at(events, 0).get("event_id").getAsString();
        String second = at(events, 1).get("event_id").getAsString();
        assertFalse(first.isBlank());
        assertNotEquals(first, second);
    }

    @Test
    void theDefaultModelIsTheDedicatedSynthesiserNotAChatModel() {
        // 反过来配过一版:实时对话模型收到"你好"会回答你,而不是念出来
        assertTrue(DashScopeTts.DEFAULT_MODEL.contains("tts"), DashScopeTts.DEFAULT_MODEL);
        assertTrue(new DashScopeTts("", "k", "", "").describe().contains(DashScopeTts.DEFAULT_MODEL));
    }

    @Test
    void aVoiceWrittenAsModelColonVoiceKeepsOnlyTheVoice() {
        // 别家后端习惯 "模型:音色" 这种写法,粘过来的配置不该把整串当音色发出去
        assertTrue(new DashScopeTts("", "k", "", "qwen3-tts:Cherry").describe().contains("voice=Cherry"));
        assertTrue(new DashScopeTts("", "k", "", "").describe()
                .contains("voice=" + DashScopeTts.DEFAULT_VOICE));
    }

    @Test
    void theModelRidesAlongAsAQueryParameter() {
        assertEquals(DashScopeTts.DEFAULT_BASE + "?model=m",
                DashScopeTts.endpoint(DashScopeTts.DEFAULT_BASE, "m").toString());
        assertEquals("wss://host/x?a=1&model=m",
                DashScopeTts.endpoint("wss://host/x?a=1", "m").toString(),
                "地址里本来就有查询参数时接 &,不是再来一个 ?");
    }

    @Test
    void describeNeverLeaksTheKey() {
        assertFalse(new DashScopeTts("", "sk-supersecret", "", "Cherry").describe().contains("supersecret"));
    }

    @Test
    void emptyTextFailsInsteadOfOpeningAConnection() {
        assertTrue(new DashScopeTts("", "k", "", "").synthesize("  ").isCompletedExceptionally());
        assertTrue(new DashScopeTts("", "k", "", "").synthesize(null).isCompletedExceptionally());
    }
}
