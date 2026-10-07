package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MimoTts} 的纯函数部分：URL 组装、请求 body 形状、响应解析与 base64 解码。
 * 网络路径（实际合成）需真机验证。
 *
 * <p>Mimo TTS 走 Chat Completions 协议（非标准 /v1/audio/speech），
 * 音频在 {@code choices[0].message.audio.data} 中以 base64 编码返回。
 */
class MimoTtsTest {

    // ---- URL 组装 ----

    @Test
    void composeUrlAppendsChatCompletions() {
        // 裸域名 → 补 /v1/chat/completions
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",
                MimoTts.composeUrl("https://api.xiaomimimo.com"));
        // 带尾斜杠 → 去斜杠再补
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",
                MimoTts.composeUrl("https://api.xiaomimimo.com/"));
        // 带 /v1 → 只补 /chat/completions
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",
                MimoTts.composeUrl("https://api.xiaomimimo.com/v1"));
        // 已带完整路径 → 直接用
        assertEquals("https://api.xiaomimimo.com/v1/chat/completions",
                MimoTts.composeUrl("https://api.xiaomimimo.com/v1/chat/completions"));
        // 本地地址 → 补 http scheme
        assertEquals("http://localhost:8080/v1/chat/completions",
                MimoTts.composeUrl("localhost:8080"));
    }

    @Test
    void composeUrlEmptyFallsBackToDefault() {
        assertEquals(MimoTts.DEFAULT_BASE + "/v1/chat/completions",
                MimoTts.composeUrl(""));
        assertEquals(MimoTts.DEFAULT_BASE + "/v1/chat/completions",
                MimoTts.composeUrl(null));
        assertEquals(MimoTts.DEFAULT_BASE + "/v1/chat/completions",
                MimoTts.composeUrl("   "));
    }

    // ---- 请求 body 形状 ----

    @Test
    void bodyCarriesChatCompletionsShape() {
        JsonObject body = MimoTts.buildBody("mimo-v2.5-tts", "mimo_default", "你好世界");

        assertEquals("mimo-v2.5-tts", body.get("model").getAsString());
        assertTrue(body.has("messages"));
        assertTrue(body.getAsJsonArray("messages").size() == 2);
        assertTrue(body.has("audio"));
    }

    @Test
    void bodyMessagesHaveCorrectRoles() {
        JsonObject body = MimoTts.buildBody("mimo-v2.5-tts", "mimo_default", "测试文本");

        var messages = body.getAsJsonArray("messages");
        // 第一条：user（音色描述，可留空）
        JsonObject userMsg = messages.get(0).getAsJsonObject();
        assertEquals("user", userMsg.get("role").getAsString());
        assertEquals("", userMsg.get("content").getAsString());
        // 第二条：assistant（待合成文本）
        JsonObject assistantMsg = messages.get(1).getAsJsonObject();
        assertEquals("assistant", assistantMsg.get("role").getAsString());
        assertEquals("测试文本", assistantMsg.get("content").getAsString());
    }

    @Test
    void bodyAudioConfiguresWavFormat() {
        JsonObject body = MimoTts.buildBody("mimo-v2.5-tts", "custom_voice", "hi");

        JsonObject audio = body.getAsJsonObject("audio");
        assertNotNull(audio);
        assertEquals("wav", audio.get("format").getAsString());
        assertEquals("custom_voice", audio.get("voice").getAsString());
    }

    // ---- base64 解码 ----

    @Test
    void extractAudioDecodesBase64Data() {
        // 构造一个最小的有效 Mimo 响应
        byte[] wavBytes = new byte[]{0x52, 0x49, 0x46, 0x46, (byte) 0xFF, 0x00};
        String b64 = Base64.getEncoder().encodeToString(wavBytes);

        String json = "{\"choices\":[{\"finish_reason\":\"stop\",\"index\":0,"
                + "\"message\":{\"content\":\"\",\"role\":\"assistant\","
                + "\"audio\":{\"id\":\"aud_001\",\"data\":\"" + b64 + "\"}}}],"
                + "\"model\":\"mimo-v2.5-tts\"}";

        byte[] result = MimoTts.extractAudio(json);
        assertArrayEquals(wavBytes, result);
    }

    @Test
    void extractAudioThrowsOnApiError() {
        String json = "{\"error\":{\"message\":\"Invalid API key\",\"type\":\"invalid_request_error\"}}";
        var ex = assertThrows(IllegalStateException.class, () -> MimoTts.extractAudio(json));
        assertTrue(ex.getMessage().contains("Invalid API key"));
    }

    @Test
    void extractAudioThrowsWhenChoicesMissing() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"model\":\"mimo-v2.5-tts\"}"));
    }

    @Test
    void extractAudioThrowsWhenChoicesEmpty() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[]}"));
    }

    @Test
    void extractAudioThrowsWhenMessageMissing() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[{\"finish_reason\":\"stop\"}]}"));
    }

    @Test
    void extractAudioThrowsWhenAudioMissing() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"));
    }

    @Test
    void extractAudioThrowsWhenDataMissing() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[{\"message\":{\"audio\":{}}}]}"));
    }

    @Test
    void extractAudioThrowsWhenDataEmpty() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[{\"message\":{\"audio\":{\"data\":\"\"}}}]}"));
    }

    @Test
    void extractAudioThrowsWhenDataNull() {
        assertThrows(IllegalStateException.class,
                () -> MimoTts.extractAudio("{\"choices\":[{\"message\":{\"audio\":{\"data\":null}}}]}"));
    }

    // ---- 构造器默认值 ----

    @Test
    void constructorUsesDefaultsForBlankInputs() {
        MimoTts tts = new MimoTts("", "", "", "");
        assertNotNull(tts.describe());
        assertTrue(tts.describe().contains(MimoTts.DEFAULT_MODEL));
        assertTrue(tts.describe().contains(MimoTts.DEFAULT_VOICE));
    }

    @Test
    void constructorUsesProvidedValues() {
        MimoTts tts = new MimoTts("https://custom.api.com", "sk-test", "my-model", "my-voice");
        String desc = tts.describe();
        assertTrue(desc.contains("my-model"));
        assertTrue(desc.contains("my-voice"));
    }
}
