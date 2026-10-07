package com.dwinovo.numen.client.stt;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 豆包语音(火山引擎)实时识别的二进制分帧编解码。
 *
 * <p>每帧都是 {@code 4 字节头 + 4 字节序号 + 4 字节负载长度 + gzip 负载},整数一律大端:
 * <pre>
 * byte0  协议版本(4) | 头长(4,单位是 4 字节)
 * byte1  消息类型(4) | 标志位(4)
 * byte2  序列化(4)   | 压缩(4)
 * byte3  保留
 * </pre>
 *
 * <p>标志位是这套协议里唯一的流程信号。客户端发最后一包时置 {@link #NEG_WITH_SEQUENCE}
 * 并把序号取负;服务端回包用 {@code 0x02} 说"这是最后一包"、{@code 0x01} 说"头后面先有
 * 4 字节序号"。中间结果和最终结果<b>是同一种消息</b>,全靠这一位分开——所以解析这一位
 * 就是 {@code onPartial} 与 {@code onFinal} 的分界。
 *
 * <p>纯 JVM,不碰 Minecraft,也不碰网络。
 */
final class DoubaoFrames {

    private DoubaoFrames() {}

    // 消息类型(byte1 高 4 位)
    private static final int FULL_CLIENT_REQUEST = 0b0001;
    private static final int AUDIO_ONLY_REQUEST = 0b0010;
    private static final int FULL_SERVER_RESPONSE = 0b1001;
    private static final int SERVER_ACK = 0b1011;
    private static final int SERVER_ERROR = 0b1111;

    // 标志位(byte1 低 4 位)
    private static final int POS_SEQUENCE = 0b0001;
    /** 最后一包:同时把序号取负。 */
    private static final int NEG_WITH_SEQUENCE = 0b0011;
    private static final int FLAG_HAS_SEQUENCE = 0x01;
    private static final int FLAG_LAST_PACKAGE = 0x02;

    private static final int PROTOCOL_V1 = 0b0001;
    private static final int HEADER_WORDS = 1;
    private static final int JSON = 0b0001;
    private static final int GZIP = 0b0001;

    /** 会话首帧:把音频格式和识别选项交上去。 */
    static byte[] fullClientRequest(int seq, String json) {
        return frame(FULL_CLIENT_REQUEST, POS_SEQUENCE, seq,
                gzip(json.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * 一块音频。{@code last} 为真时置最后一包标志并把序号取负——服务端据此收尾并回最终结果,
     * 不是靠关连接。
     */
    static byte[] audioRequest(int seq, byte[] pcm, boolean last) {
        return frame(AUDIO_ONLY_REQUEST,
                last ? NEG_WITH_SEQUENCE : POS_SEQUENCE,
                last ? -seq : seq,
                gzip(pcm));
    }

    private static byte[] frame(int messageType, int flags, int seq, byte[] payload) {
        return ByteBuffer.allocate(12 + payload.length)
                .put((byte) ((PROTOCOL_V1 << 4) | HEADER_WORDS))
                .put((byte) ((messageType << 4) | flags))
                .put((byte) ((JSON << 4) | GZIP))
                .put((byte) 0)
                .putInt(seq)
                .putInt(payload.length)
                .put(payload)
                .array();
    }

    /**
     * 服务端的一帧。
     *
     * @param last  最后一包——这一帧的 {@code text} 就是最终转写
     * @param error 出错原因;{@code null} 表示这帧正常
     * @param text  到目前为止的完整转写;这一帧没带就是 {@code null}
     */
    record Reply(boolean last, String error, String text) {}

    /** 读一帧。读不动就当出错——半截转写不能当成功。 */
    static Reply parse(byte[] frame) {
        if (frame == null || frame.length < 4) {
            return new Reply(false, "回包太短", null);
        }
        int headerBytes = (frame[0] & 0x0F) * 4;
        int messageType = (frame[1] & 0xF0) >>> 4;
        int flags = frame[1] & 0x0F;
        int serialization = (frame[2] & 0xF0) >>> 4;
        int compression = frame[2] & 0x0F;
        boolean last = (flags & FLAG_LAST_PACKAGE) != 0;

        if (headerBytes > frame.length) {
            return new Reply(last, "回包头长越界", null);
        }
        ByteBuffer buf = ByteBuffer.wrap(frame, headerBytes, frame.length - headerBytes);
        if ((flags & FLAG_HAS_SEQUENCE) != 0) {
            skip(buf, 4);          // 序号,我们按发送顺序自己记账,不用它
        }
        String errorCode = null;
        if (messageType == SERVER_ERROR) {
            errorCode = buf.remaining() >= 4 ? String.valueOf(buf.getInt() & 0xFFFFFFFFL) : "?";
        } else if (messageType == SERVER_ACK) {
            skip(buf, 4);          // ack 自带一个序号,同样不用
        }
        if (buf.remaining() < 4) {
            return errorCode == null ? new Reply(last, null, null)
                    : new Reply(last, "服务端错误 " + errorCode, null);
        }

        int declared = buf.getInt();
        // 长度以实到字节为准:头里写多了也不能越界读
        byte[] payload = new byte[Math.max(0, Math.min(declared, buf.remaining()))];
        buf.get(payload);
        String body;
        try {
            body = new String(compression == GZIP ? gunzip(payload) : payload, StandardCharsets.UTF_8);
        } catch (UncheckedIOException e) {
            return new Reply(last, "回包解压失败:" + e.getMessage(), null);
        }
        if (errorCode != null) {
            return new Reply(last, "服务端错误 " + errorCode + ":" + body, null);
        }
        if (serialization != JSON) {
            return new Reply(last, null, body.isBlank() ? null : body);
        }
        return new Reply(last, null, textOf(body));
    }

    /**
     * 取 {@code result} 里那句话;取不到返回 {@code null}(=这帧没带文本)。
     *
     * <p>{@code result} 两种形状都吃:识别 1.0 给的是一个对象,2.0 给的是一个数组。同一个端点
     * 按 resource id 走不同模型,所以两种都会遇到。
     */
    private static String textOf(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            if (!root.has("result")) {
                return null;
            }
            JsonElement result = root.get("result");
            if (result.isJsonArray()) {
                StringBuilder joined = new StringBuilder();
                for (JsonElement item : result.getAsJsonArray()) {
                    String text = textOfObject(item);
                    if (text != null) {
                        joined.append(text);
                    }
                }
                return joined.isEmpty() ? null : joined.toString();
            }
            return textOfObject(result);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String textOfObject(JsonElement element) {
        if (!element.isJsonObject()) {
            return null;
        }
        JsonObject object = element.getAsJsonObject();
        if (!object.has("text") || object.get("text").isJsonNull()) {
            return null;
        }
        String text = object.get("text").getAsString();
        return text.isEmpty() ? null : text;
    }

    private static void skip(ByteBuffer buf, int n) {
        buf.position(buf.position() + Math.min(n, buf.remaining()));
    }

    private static byte[] gzip(byte[] raw) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(raw.length / 2 + 32);
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(raw);
        } catch (IOException e) {
            throw new UncheckedIOException(e);   // 写内存流不会失败
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] packed) {
        try (GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(packed))) {
            return gz.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
