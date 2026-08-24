package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MiniMaxTts} 的纯函数部分:URL 组装(含 GroupId)、请求 body 形状、
 * 响应解析与 hex 解码。网络路径需真机验证。
 */
class MiniMaxTtsTest {

    // ---- URL 组装 ----

    @Test
    void composeUrlAppendsPath() {
        assertEquals("https://api.minimax.io/v1/t2a_v2",
                MiniMaxTts.composeUrl("https://api.minimax.io", ""));
        assertEquals("https://api.minimax.io/v1/t2a_v2",
                MiniMaxTts.composeUrl("https://api.minimax.io/", null));
        assertEquals("https://api.minimaxi.com/v1/t2a_v2",
                MiniMaxTts.composeUrl("https://api.minimaxi.com/v1/t2a_v2", ""));
    }

    @Test
    void composeUrlAppendsGroupIdWhenPresent() {
        assertEquals("https://api.minimaxi.com/v1/t2a_v2?GroupId=1234567890",
                MiniMaxTts.composeUrl("https://api.minimaxi.com", " 1234567890 "));
        // 留空不携带(新版接入不需要)。
        assertFalse(MiniMaxTts.composeUrl("https://api.minimax.io", "  ").contains("GroupId"));
    }

    // ---- 请求 body 形状 ----

    @Test
    void bodyCarriesWavHexNonStreaming() {
        JsonObject body = MiniMaxTts.buildBody("speech-02-turbo", "male-qn-qingse", "你好");
        assertEquals("speech-02-turbo", body.get("model").getAsString());
        assertEquals("你好", body.get("text").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals("hex", body.get("output_format").getAsString());
        assertEquals("male-qn-qingse",
                body.getAsJsonObject("voice_setting").get("voice_id").getAsString());
        JsonObject audio = body.getAsJsonObject("audio_setting");
        assertEquals("wav", audio.get("format").getAsString());
        assertEquals(32_000, audio.get("sample_rate").getAsInt());
        assertEquals(1, audio.get("channel").getAsInt());
    }

    // ---- hex 解码 ----

    @Test
    void hexDecodeRoundTrip() {
        assertArrayEquals(new byte[]{0x52, 0x49, 0x46, 0x46, (byte) 0xFF, 0x00},
                MiniMaxTts.hexDecode("52494646ff00"));
        assertArrayEquals(new byte[]{(byte) 0xAB}, MiniMaxTts.hexDecode(" AB "));   // 容忍大写与空白
        assertArrayEquals(new byte[0], MiniMaxTts.hexDecode(""));
    }

    @Test
    void hexDecodeRejectsOddLengthAndGarbage() {
        assertThrows(IllegalStateException.class, () -> MiniMaxTts.hexDecode("abc"));
        assertThrows(IllegalStateException.class, () -> MiniMaxTts.hexDecode("zz"));
    }

    // ---- 响应解析 ----

    @Test
    void extractAudioDecodesDataAudio() {
        String json = "{\"data\":{\"audio\":\"52494646\",\"status\":2},"
                + "\"base_resp\":{\"status_code\":0,\"status_msg\":\"success\"}}";
        assertArrayEquals(new byte[]{0x52, 0x49, 0x46, 0x46}, MiniMaxTts.extractAudio(json));
    }

    @Test
    void extractAudioThrowsOnBusinessError() {
        // HTTP 200 也可能带业务错误:base_resp.status_code != 0,错误原文要进异常消息。
        String json = "{\"base_resp\":{\"status_code\":1004,\"status_msg\":\"invalid api key\"}}";
        var ex = assertThrows(IllegalStateException.class, () -> MiniMaxTts.extractAudio(json));
        assertTrue(ex.getMessage().contains("1004"));
        assertTrue(ex.getMessage().contains("invalid api key"));
    }

    @Test
    void extractAudioThrowsWhenAudioMissing() {
        assertThrows(IllegalStateException.class,
                () -> MiniMaxTts.extractAudio("{\"base_resp\":{\"status_code\":0},\"data\":{}}"));
        assertThrows(IllegalStateException.class,
                () -> MiniMaxTts.extractAudio("{\"base_resp\":{\"status_code\":0}}"));
    }
}
