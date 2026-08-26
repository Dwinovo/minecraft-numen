package com.dwinovo.numen.client.voice;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 豆包 TTS:URL 拼接、请求体形状、NDJSON 分片拼接与错误码。 */
class DoubaoTtsTest {

    private static final String PATH = "/api/v3/tts/unidirectional";

    // ---- composeUrl ----

    @Test
    void blankUrlFallsBackToOfficialEndpoint() {
        assertEquals(DoubaoTts.DEFAULT_BASE + PATH, DoubaoTts.composeUrl(""));
        assertEquals(DoubaoTts.DEFAULT_BASE + PATH, DoubaoTts.composeUrl(null));
    }

    @Test
    void trailingSlashesAreTrimmedBeforeAppending() {
        assertEquals("https://example.com" + PATH, DoubaoTts.composeUrl("https://example.com///"));
    }

    @Test
    void fullPathIsUsedAsIs() {
        String full = "https://example.com" + PATH;
        assertEquals(full, DoubaoTts.composeUrl(full));
    }

    @Test
    void schemelessHostGetsHttps() {
        assertEquals("https://example.com" + PATH, DoubaoTts.composeUrl("example.com"));
    }

    // ---- buildBody ----

    @Test
    void bodyAsksForPcmSoWavCodecCanTakeItDirectly() {
        JsonObject body = DoubaoTts.buildBody("你好", "zh_female_x_bigtts");
        JsonObject params = body.getAsJsonObject("req_params");
        assertEquals("你好", params.get("text").getAsString());
        assertEquals("zh_female_x_bigtts", params.get("speaker").getAsString());
        JsonObject audio = params.getAsJsonObject("audio_params");
        // mp3 是服务端默认值,我们必须显式要 pcm——JDK 没有 mp3 解码器
        assertEquals("pcm", audio.get("format").getAsString());
        assertEquals(DoubaoTts.SAMPLE_RATE, audio.get("sample_rate").getAsInt());
    }

    // ---- parseChunks ----

    @Test
    void joinsBase64ChunksInOrder() {
        String line1 = "{\"code\":0,\"data\":\"" + b64(new byte[]{1, 2}) + "\"}";
        String line2 = "{\"code\":0,\"data\":\"" + b64(new byte[]{3, 4}) + "\"}";
        String done = "{\"code\":20000000,\"message\":\"ok\"}";
        assertArrayEquals(new byte[]{1, 2, 3, 4},
                DoubaoTts.parseChunks(line1 + "\n" + line2 + "\n" + done));
    }

    @Test
    void blankLinesAreSkipped() {
        String line = "{\"code\":0,\"data\":\"" + b64(new byte[]{7}) + "\"}";
        assertArrayEquals(new byte[]{7},
                DoubaoTts.parseChunks("\n  \n" + line + "\n\n{\"code\":20000000}\n"));
    }

    @Test
    void stopsAtTerminalPacketAndIgnoresTrailingGarbage() {
        String line = "{\"code\":0,\"data\":\"" + b64(new byte[]{9}) + "\"}";
        // 收尾包之后不该再往下读——真出现残行也不能让整句白合成
        assertArrayEquals(new byte[]{9},
                DoubaoTts.parseChunks(line + "\n{\"code\":20000000}\nnot json at all"));
    }

    @Test
    void serverErrorCodeCarriesItsMessage() {
        var e = assertThrows(IllegalStateException.class, () -> DoubaoTts.parseChunks(
                "{\"code\":45000000,\"message\":\"speaker permission denied\"}"));
        assertTrue(e.getMessage().contains("45000000"), e.getMessage());
        assertTrue(e.getMessage().contains("speaker permission denied"), e.getMessage());
    }

    @Test
    void emptyResponseIsAnError() {
        assertThrows(IllegalStateException.class, () -> DoubaoTts.parseChunks(""));
    }

    @Test
    void terminalPacketWithoutAudioIsAnError() {
        assertThrows(IllegalStateException.class,
                () -> DoubaoTts.parseChunks("{\"code\":20000000,\"message\":\"ok\"}"));
    }

    @Test
    void invalidBase64IsRejected() {
        assertThrows(IllegalStateException.class,
                () -> DoubaoTts.parseChunks("{\"code\":0,\"data\":\"!!!not base64!!!\"}"));
    }

    // ---- 必填项 ----

    @Test
    void missingKeyOrSpeakerFailsWithoutTouchingTheNetwork() {
        var noKey = new DoubaoTts("", "", "", "zh_female_x_bigtts").synthesize("嗨");
        assertTrue(noKey.isCompletedExceptionally());
        assertThrows(ExecutionException.class, noKey::get);

        var noSpeaker = new DoubaoTts("", "key", "", "  ").synthesize("嗨");
        assertTrue(noSpeaker.isCompletedExceptionally());
        assertThrows(ExecutionException.class, noSpeaker::get);
    }

    @Test
    void describeNeverLeaksTheKey() {
        String d = new DoubaoTts("", "super-secret-key", "", "zh_female_x_bigtts").describe();
        assertTrue(d.contains("doubao"), d);
        assertFalse(d.contains("super-secret-key"), d);
    }

    @Test
    void blankResourceIdFallsBackToTheTwoPointZeroModel() {
        assertTrue(new DoubaoTts("", "k", "  ", "v").describe()
                .contains(DoubaoTts.DEFAULT_RESOURCE_ID));
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
