package com.dwinovo.numen.client.stt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 百炼实时识别的指令形状与事件解析。
 *
 * <p>{@code sentence_end} 是这套协议里区分"还在改"和"这句定了"的唯一依据,也就是
 * {@code onPartial} 与最终结果的分界。心跳包长得跟结果包一样但不带文字——把它当结果的话
 * 会把已经听到的字清空。
 */
class DashScopeSttTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ---- 指令 ----

    @Test
    void runTaskSaysWhatTheOfficialProtocolExpects() {
        JsonObject root = json(DashScopeStt.runTask("task-1", "m"));
        JsonObject header = root.getAsJsonObject("header");
        assertEquals("run-task", header.get("action").getAsString());
        assertEquals("task-1", header.get("task_id").getAsString());
        assertEquals("duplex", header.get("streaming").getAsString());

        JsonObject payload = root.getAsJsonObject("payload");
        assertEquals("audio", payload.get("task_group").getAsString());
        assertEquals("asr", payload.get("task").getAsString());
        assertEquals("recognition", payload.get("function").getAsString());
        assertEquals("m", payload.get("model").getAsString());
        assertTrue(payload.has("input"), "input 是空对象也得在场");
    }

    @Test
    void theAudioFormatFollowsTheCaptureFormatInsteadOfRepeatingIt() {
        JsonObject parameters = json(DashScopeStt.runTask("t", "m"))
                .getAsJsonObject("payload").getAsJsonObject("parameters");
        assertEquals("pcm", parameters.get("format").getAsString());
        assertEquals((int) SttAudio.FORMAT.getSampleRate(), parameters.get("sample_rate").getAsInt());
    }

    @Test
    void finishTaskCarriesTheSameTaskId() {
        // task_id 对不上服务端会当成另一个任务,结果就永远等不回来
        JsonObject header = json(DashScopeStt.finishTask("task-1")).getAsJsonObject("header");
        assertEquals("finish-task", header.get("action").getAsString());
        assertEquals("task-1", header.get("task_id").getAsString());
        assertEquals("duplex", header.get("streaming").getAsString());
    }

    // ---- 事件 ----

    @Test
    void sentenceEndIsWhatSeparatesADraftFromAFinishedSentence() {
        var draft = DashScopeStt.readEvent("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"text":"挖一点","sentence_end":false}}}}""");
        assertEquals("result-generated", draft.name());
        assertEquals("挖一点", draft.text());
        assertFalse(draft.sentenceEnd());

        var done = DashScopeStt.readEvent("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"text":"挖一点铁。","sentence_end":true}}}}""");
        assertTrue(done.sentenceEnd());
        assertEquals("挖一点铁。", done.text());
    }

    @Test
    void aHeartbeatCarriesNoTextAndMustNotWipeWhatWeHave() {
        var beat = DashScopeStt.readEvent("""
                {"header":{"event":"result-generated"},
                 "payload":{"output":{"sentence":{"heartbeat":true,"sentence_end":false}}}}""");
        assertNull(beat.text(), "没有 text 字段就该是 null,不是空串");
        assertNull(beat.error());
    }

    @Test
    void taskFailedBecomesAnErrorWithBothCodeAndMessage() {
        var failed = DashScopeStt.readEvent("""
                {"header":{"event":"task-failed","error_code":"CLIENT_ERROR",
                 "error_message":"request timeout after 23 seconds."}}""");
        assertNotNull(failed.error());
        assertTrue(failed.error().contains("CLIENT_ERROR"), failed.error());
        assertTrue(failed.error().contains("timeout"), failed.error());
    }

    @Test
    void lifecycleEventsAreRecognisedByName() {
        assertEquals("task-started", DashScopeStt.readEvent(
                "{\"header\":{\"event\":\"task-started\"},\"payload\":{}}").name());
        assertEquals("task-finished", DashScopeStt.readEvent(
                "{\"header\":{\"event\":\"task-finished\"},\"payload\":{\"output\":{}}}").name());
    }

    @Test
    void unreadableEventsAreIgnoredRatherThanTreatedAsFailures() {
        // 协议里还有我们不认的事件;读不懂当"没这条",不能把一次正常录音判死
        for (String bad : new String[]{"", "not json", "{}", "{\"header\":{}}", "[]"}) {
            var event = DashScopeStt.readEvent(bad);
            assertNull(event.error(), bad);
            assertNull(event.text(), bad);
        }
    }

    // ---- 配置 ----

    @Test
    void blanksFallBackToTheDocumentedEndpointAndModel() {
        String described = new DashScopeStt("", "k", "").describe();
        assertTrue(described.contains(DashScopeStt.DEFAULT_URL), described);
        assertTrue(described.contains(DashScopeStt.DEFAULT_MODEL), described);
        assertTrue(new DashScopeStt(null, null, null).describe().contains(DashScopeStt.DEFAULT_MODEL));
    }

    @Test
    void describeNeverLeaksTheKey() {
        assertFalse(new DashScopeStt("", "sk-supersecret", "").describe().contains("supersecret"));
    }
}
