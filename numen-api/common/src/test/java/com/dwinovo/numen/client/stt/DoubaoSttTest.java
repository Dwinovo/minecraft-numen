package com.dwinovo.numen.client.stt;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 豆包实时识别的分帧与凭据。
 *
 * <p>这层最要紧的是 {@link DoubaoFrames#parse} 的<b>失败方向</b>:读不懂只能往"出错"倒,
 * 不能悄悄变成一句空转写——那样主人以为自己没说清楚,其实是协议对不上。
 */
class DoubaoSttTest {

    // ---- 发出去的帧 ----

    @Test
    void everyFrameCarriesTheDocumentedFourByteHeader() {
        byte[] frame = DoubaoFrames.fullClientRequest(1, "{}");

        assertEquals(0x11, frame[0] & 0xFF, "协议版本 1 + 头长 1(单位 4 字节)");
        assertEquals(0x11, frame[1] & 0xFF, "首帧 = 类型 0b0001 + 带正序号 0b0001");
        assertEquals(0x11, frame[2] & 0xFF, "JSON + gzip");
        assertEquals(0x00, frame[3] & 0xFF, "保留位");
    }

    @Test
    void theLengthFieldMatchesWhatActuallyFollows() {
        byte[] frame = DoubaoFrames.audioRequest(7, new byte[]{1, 2, 3, 4}, false);
        ByteBuffer buf = ByteBuffer.wrap(frame);
        buf.position(4);

        assertEquals(7, buf.getInt(), "序号");
        assertEquals(frame.length - 12, buf.getInt(), "长度字段说的和后面实到的一致");
    }

    @Test
    void audioSurvivesTheGzipRoundTrip() {
        byte[] pcm = new byte[3200];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (byte) (i * 31);
        }
        assertArrayEquals(pcm, gunzip(payloadOf(DoubaoFrames.audioRequest(2, pcm, false))));
    }

    @Test
    void theLastPacketFlipsTheFlagAndNegatesTheSequence() {
        // 结束信号只有这一处:标志位 0b0011 + 序号取负。服务端据此收尾并回最终结果——
        // 不是靠关连接,所以这两样错一个,onFinal 就永远等不到。
        byte[] last = DoubaoFrames.audioRequest(9, new byte[]{1}, true);

        assertEquals(0x23, last[1] & 0xFF, "类型 0b0010 + 最后一包 0b0011");
        assertEquals(-9, ByteBuffer.wrap(last).getInt(4));

        byte[] mid = DoubaoFrames.audioRequest(9, new byte[]{1}, false);
        assertEquals(0x21, mid[1] & 0xFF, "中间包是正序号标志");
        assertEquals(9, ByteBuffer.wrap(mid).getInt(4));
    }

    @Test
    void theFirstFrameCarriesTheSessionJson() {
        String json = new String(gunzip(payloadOf(
                DoubaoFrames.fullClientRequest(1, DoubaoStt.sessionJson()))), StandardCharsets.UTF_8);
        assertEquals(DoubaoStt.sessionJson(), json);
    }

    // ---- 收回来的帧 ----

    @Test
    void partialAndFinalDifferOnlyByOneFlagBit() {
        String body = "{\"result\":{\"text\":\"挖一百二十八个铁\"}}";

        DoubaoFrames.Reply partial = DoubaoFrames.parse(serverJson(0b0001, body));
        assertFalse(partial.last(), "0x02 没置位 = 还在说");
        assertEquals("挖一百二十八个铁", partial.text());
        assertNull(partial.error());

        DoubaoFrames.Reply done = DoubaoFrames.parse(serverJson(0b0011, body));
        assertTrue(done.last(), "0x02 置位 = 这就是最终转写");
        assertEquals("挖一百二十八个铁", done.text());
    }

    @Test
    void resultIsReadWhetherItIsAnObjectOrAList() {
        // 识别 1.0 给对象、2.0 给数组;同一个端点按 resource id 走不同模型,两种都会遇到
        assertEquals("有了", DoubaoFrames.parse(
                serverJson(0b0001, "{\"result\":{\"text\":\"有了\"}}")).text());
        assertEquals("有了", DoubaoFrames.parse(
                serverJson(0b0001, "{\"result\":[{\"text\":\"有了\"}]}")).text());
        assertEquals("前半后半", DoubaoFrames.parse(
                serverJson(0b0001, "{\"result\":[{\"text\":\"前半\"},{\"text\":\"后半\"}]}")).text());
    }

    @Test
    void aFrameWithoutAResultCarriesNoText() {
        // 握手回执、纯 ack 都长这样:不是错,只是这帧没带字,不该覆盖已有转写
        DoubaoFrames.Reply reply = DoubaoFrames.parse(serverJson(0b0001, "{\"audio_info\":{\"duration\":120}}"));
        assertNull(reply.error());
        assertNull(reply.text());
    }

    @Test
    void serverErrorsBecomeErrorsNotEmptyTranscripts() {
        DoubaoFrames.Reply reply = DoubaoFrames.parse(serverError(45000001, "invalid token"));
        assertNotNull(reply.error());
        assertTrue(reply.error().contains("45000001"), reply.error());
        assertTrue(reply.error().contains("invalid token"), reply.error());
        assertNull(reply.text());
    }

    @Test
    void garbledFramesFallToErrorNotToSuccess() {
        // 方向是有意的:报错主人看得见、能改;悄悄回一句空转写他只会以为自己没说清楚
        assertNotNull(DoubaoFrames.parse(null).error());
        assertNotNull(DoubaoFrames.parse(new byte[0]).error());
        assertNotNull(DoubaoFrames.parse(new byte[]{0x11, 0x11, 0x11}).error());
        assertNotNull(DoubaoFrames.parse(new byte[]{(byte) 0x1F, 0x11, 0x11, 0x00}).error(),
                "头长字段说 15 个字(60 字节),实到 4 字节");
    }

    @Test
    void aTruncatedPayloadIsNotReadPastTheEndOfTheFrame() {
        byte[] frame = serverJson(0b0001, "{\"result\":{\"text\":\"够了\"}}");
        byte[] cut = new byte[frame.length - 5];
        System.arraycopy(frame, 0, cut, 0, cut.length);
        // 长度字段还写着完整长度,实到少 5 字节:只能报错,不能越界读
        assertNotNull(DoubaoFrames.parse(cut).error());
    }

    // ---- 凭据 ----

    @Test
    void aPlainApiKeyGoesOutAsTheNewConsoleHeader() {
        // 新版控制台只发一串 UUID 样的 key,握手验过:这个头对、老的那两个头会 400
        var headers = DoubaoStt.authHeaders("51e24a12-5224-41f5-838b-ce55ca639d27");
        assertEquals(Map.of("X-Api-Key", "51e24a12-5224-41f5-838b-ce55ca639d27"), headers);
    }

    @Test
    void oldConsoleCredentialsSplitOnTheFirstColon() {
        var headers = DoubaoStt.authHeaders(" 1234567890 : abcDEF ");
        assertEquals("1234567890", headers.get("X-Api-App-Key"));
        assertEquals("abcDEF", headers.get("X-Api-Access-Key"));
        assertFalse(headers.containsKey("X-Api-Key"), "旧版就别再发新版那个头");
    }

    @Test
    void onlyTheFirstColonSplits() {
        // token 里再有冒号也是 token 的一部分
        var headers = DoubaoStt.authHeaders("app:a:b:c");
        assertEquals("app", headers.get("X-Api-App-Key"));
        assertEquals("a:b:c", headers.get("X-Api-Access-Key"));
    }

    @Test
    void anEmptyKeyStillProducesAWellFormedHeaderMap() {
        // 空 key 由会话开头拦下报"没填 API Key",这里只要不炸
        assertEquals(Map.of("X-Api-Key", ""), DoubaoStt.authHeaders(null));
        assertEquals(Map.of("X-Api-Key", ""), DoubaoStt.authHeaders("   "));
    }

    // ---- 会话参数 ----

    @Test
    void theSessionFollowsTheCaptureFormatInsteadOfRepeatingIt() {
        // 采集格式改了这里必须跟着改,所以它从 SttAudio.FORMAT 取,不另写一份数字
        JsonObject audio = JsonParser.parseString(DoubaoStt.sessionJson())
                .getAsJsonObject().getAsJsonObject("audio");

        assertEquals((int) SttAudio.FORMAT.getSampleRate(), audio.get("rate").getAsInt());
        assertEquals(SttAudio.FORMAT.getSampleSizeInBits(), audio.get("bits").getAsInt());
        assertEquals(SttAudio.FORMAT.getChannels(), audio.get("channel").getAsInt());
        assertEquals("pcm", audio.get("format").getAsString(), "喂的是裸 PCM,没有 WAV 头");
        assertEquals("raw", audio.get("codec").getAsString());
    }

    @Test
    void theSessionAsksForPunctuationAndDigits() {
        // 说出来的是"一百二十八",落进输入框该是 "128" —— 主人是拿它当指令用的
        JsonObject request = JsonParser.parseString(DoubaoStt.sessionJson())
                .getAsJsonObject().getAsJsonObject("request");
        assertEquals("bigmodel", request.get("model_name").getAsString());
        assertTrue(request.get("enable_itn").getAsBoolean());
        assertTrue(request.get("enable_punc").getAsBoolean());
    }

    @Test
    void theBackendFallsBackToTheDocumentedEndpointAndTier() {
        assertTrue(new DoubaoStt("", "k", "").describe().contains(DoubaoStt.DEFAULT_URL));
        assertTrue(new DoubaoStt(null, "k", null).describe().contains(DoubaoStt.DEFAULT_RESOURCE_ID));
        assertTrue(DoubaoStt.DEFAULT_RESOURCE_ID.contains("seedasr"), "缺省档是识别 2.0");
    }

    @Test
    void describeNeverLeaksTheKey() {
        assertFalse(new DoubaoStt("", "supersecret", "").describe().contains("supersecret"));
        assertFalse(new DoubaoStt("", "1234:supersecret", "").describe().contains("supersecret"));
    }

    // ---- 造服务端的帧 ----

    /** {@code FULL_SERVER_RESPONSE} + gzip JSON。 */
    private static byte[] serverJson(int flags, String json) {
        byte[] payload = gzip(json.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(12 + payload.length);
        buf.put((byte) 0x11).put((byte) ((0b1001 << 4) | flags)).put((byte) 0x11).put((byte) 0);
        buf.putInt(1);                    // 序号(flags 里带 0x01 才有)
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    /** {@code SERVER_ERROR_RESPONSE}:错误码在前,消息在后。 */
    private static byte[] serverError(int code, String message) {
        byte[] payload = gzip(message.getBytes(StandardCharsets.UTF_8));
        ByteBuffer buf = ByteBuffer.allocate(12 + payload.length);
        buf.put((byte) 0x11).put((byte) (0b1111 << 4)).put((byte) 0x11).put((byte) 0);
        buf.putInt(code);
        buf.putInt(payload.length);
        buf.put(payload);
        return buf.array();
    }

    private static byte[] payloadOf(byte[] frame) {
        byte[] out = new byte[frame.length - 12];
        System.arraycopy(frame, 12, out, 0, out.length);
        return out;
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] packed) {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(packed))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
