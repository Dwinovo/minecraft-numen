package com.dwinovo.numen.client.stt;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶跃 ASR 的 SSE 解析与 URL 组装。
 *
 * <p>解析层的要紧处与豆包同一条纪律:读不懂只能往"出错"倒——done 缺文本字段、
 * 流结束没有 done、data 不是 JSON,都必须是明确的错误,不能悄悄变成空转写。
 */
class StepFunSttTest {

    private static final class Rec implements StepFunStt.SseTranscript.Sink {
        final List<String> deltas = new ArrayList<>();
        String done;
        String error;

        @Override public void delta(String text) { deltas.add(text); }

        @Override public void done(String text) { done = text; }

        @Override public void error(String message) { error = message; }
    }

    private static void feed(StepFunStt.SseTranscript t, String... lines) {
        for (String l : lines) t.acceptLine(l);
    }

    @Test
    void deltasStreamThenDoneWins() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t,
                "event: transcript.text.delta", "data: {\"delta\":\"你好\"}", "",
                "event: transcript.text.delta", "data: {\"delta\":\"世界\"}", "",
                "event: transcript.text.done", "data: {\"text\":\"你好,世界\"}", "");
        t.end();
        assertEquals(List.of("你好", "世界"), rec.deltas);
        assertEquals("你好,世界", rec.done);   // done 全文是权威结果,不是 delta 拼接
        assertEquals(null, rec.error);
    }

    @Test
    void streamEndingWithoutDoneIsAnError() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t, "event: transcript.text.delta", "data: {\"delta\":\"半截\"}", "");
        t.end();
        assertEquals(null, rec.done);
        assertTrue(rec.error != null && rec.error.contains("done"));
    }

    @Test
    void doneWithoutTextFieldIsAnErrorNotEmptyTranscript() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t, "event: transcript.text.done", "data: {\"usage\":{\"tokens\":3}}", "");
        assertEquals(null, rec.done);
        assertTrue(rec.error != null && rec.error.contains("文本字段"));
    }

    @Test
    void errorEventCarriesRawPayload() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t, "event: error", "data: {\"code\":\"invalid_api_key\"}", "");
        assertTrue(rec.error != null && rec.error.contains("invalid_api_key"));
    }

    @Test
    void nonJsonDataFailsLoudly() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t, "event: transcript.text.done", "data: <html>bad gateway</html>", "");
        assertTrue(rec.error != null && rec.error.contains("JSON"));
    }

    @Test
    void unknownEventsAreIgnoredUntilDone() {
        Rec rec = new Rec();
        StepFunStt.SseTranscript t = new StepFunStt.SseTranscript(rec);
        feed(t,
                "event: meta.heartbeat", "data: {\"ok\":true}", "",
                "event: transcript.text.done", "data: {\"text\":\"好\"}", "");
        assertEquals("好", rec.done);
        assertEquals(null, rec.error);
    }

    @Test
    void composeUrlToleratesCommonShapes() {
        assertEquals("https://api.stepfun.com/v1/audio/asr/sse", StepFunStt.composeUrl(""));
        assertEquals("https://api.stepfun.com/v1/audio/asr/sse", StepFunStt.composeUrl("api.stepfun.com"));
        assertEquals("https://api.stepfun.com/v1/audio/asr/sse", StepFunStt.composeUrl("https://api.stepfun.com/v1"));
        assertEquals("https://x.example/v1/audio/asr/sse", StepFunStt.composeUrl("https://x.example/v1/audio/asr/sse"));
        assertEquals("http://127.0.0.1:8080/v1/audio/asr/sse", StepFunStt.composeUrl("http://127.0.0.1:8080/"));
    }
}
