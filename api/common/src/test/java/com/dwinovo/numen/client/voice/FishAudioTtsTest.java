package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link FishAudioTts} 的纯函数部分:URL 组装与请求 body 形状。
 * 网络路径(裸音频响应、model 请求头)需真机验证。
 */
class FishAudioTtsTest {

    @Test
    void composeUrlAppendsPath() {
        assertEquals("https://api.fish.audio/v1/tts", FishAudioTts.composeUrl("https://api.fish.audio"));
        assertEquals("https://api.fish.audio/v1/tts", FishAudioTts.composeUrl("https://api.fish.audio/"));
        assertEquals("https://api.fish.audio/v1/tts", FishAudioTts.composeUrl("https://api.fish.audio/v1/tts"));
        assertEquals("http://my-proxy:8080/v1/tts", FishAudioTts.composeUrl("http://my-proxy:8080"));
    }

    @Test
    void bodyCarriesTextReferenceAndWav() {
        JsonObject body = FishAudioTts.buildBody("你好,我是你的同伴。", "802e3bc2b27e49c2995d23ef70e6ac89");
        assertEquals("你好,我是你的同伴。", body.get("text").getAsString());
        assertEquals("802e3bc2b27e49c2995d23ef70e6ac89", body.get("reference_id").getAsString());
        assertEquals("wav", body.get("format").getAsString());
    }

    @Test
    void blankReferenceIdIsOmitted() {
        // reference_id 留空不发字段——用账号默认声线,发空串反而 400。
        assertFalse(FishAudioTts.buildBody("hi", "").has("reference_id"));
        assertFalse(FishAudioTts.buildBody("hi", "   ").has("reference_id"));
        assertFalse(FishAudioTts.buildBody("hi", null).has("reference_id"));
    }

    @Test
    void referenceIdIsTrimmed() {
        assertEquals("abc123",
                FishAudioTts.buildBody("hi", " abc123 ").get("reference_id").getAsString());
    }
}
