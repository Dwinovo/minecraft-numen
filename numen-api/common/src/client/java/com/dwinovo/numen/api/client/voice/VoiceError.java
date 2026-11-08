package com.dwinovo.numen.api.client.voice;

import java.util.List;

/**
 * 语音这一块(合成 TTS、识别 STT)的失败:原因码 + 现场的原话。产生错误的那一层只说"是哪一种、厂家说了什么",
 * 不写给人看的话;界面({@code VoiceWords})把码按当前语言翻出来再接上原话,所以同一个错在哪里看到都是同一句,
 * 换了语言也跟着变。{@link #getMessage()} 是给日志的诊断(码 + 原话,不分语言)。
 *
 * <p>{@code args} 按码约定的顺序:
 * <ul>
 *   <li>{@link #HTTP}:{@code [服务名, 状态码, 厂家的原话]};</li>
 *   <li>{@link #API}:{@code [服务名, 厂家的原话]}(回应是 200 但里面说出错了,或协议里的错误事件);</li>
 *   <li>{@link #REPLY_MISSING}:{@code [服务名, 缺的字段]};{@link #REPLY_BAD}:{@code [服务名, 哪里读不了]};</li>
 *   <li>{@link #NO_KEY}、{@link #NO_VOICE}:{@code [服务名]};{@link #BAD_URL}:{@code [填的地址]};</li>
 *   <li>{@link #CLOSED}:{@code [关闭码, 对方给的理由]};{@link #BAD_WAV}:{@code [哪里不对]};</li>
 *   <li>{@link #UNKNOWN}:{@code [异常类型, 异常信息]},不是我们认得的错。</li>
 * </ul>
 * 服务名是商标,不翻译;"哪里读不了/哪里不对/缺的字段"是协议里的技术词,同样原样给人看。
 */
public final class VoiceError extends IllegalStateException {

    public static final String HTTP = "http";
    public static final String API = "api";
    public static final String REPLY_MISSING = "reply_missing";
    public static final String REPLY_BAD = "reply_bad";
    public static final String NO_KEY = "no_key";
    public static final String NO_VOICE = "no_voice";
    public static final String NO_URL = "no_url";
    public static final String BAD_URL = "bad_url";
    public static final String EMPTY_TEXT = "empty_text";
    public static final String EMPTY_AUDIO = "empty_audio";
    public static final String CLOSED = "closed";
    public static final String BAD_WAV = "bad_wav";
    public static final String UNKNOWN = "unknown";

    private final String code;
    private final List<String> args;

    private VoiceError(String code, List<String> args, Throwable cause) {
        super(code + (args.isEmpty() ? "" : " " + args), cause);
        this.code = code;
        this.args = args;
    }

    public static VoiceError of(String code, String... args) {
        return new VoiceError(code, norm(args), null);
    }

    /** 带着底下的异常(解析失败之类)一起抛,日志里能看到原因。 */
    public static VoiceError of(Throwable cause, String code, String... args) {
        return new VoiceError(code, norm(args), cause);
    }

    /** HTTP 错:服务名、状态码、响应体里挖出的原话(没有可为空)。 */
    public static VoiceError http(String service, int status, String reply) {
        return of(HTTP, service, String.valueOf(status), reply);
    }

    /** WebSocket 被对方关了。 */
    public static VoiceError closed(int status, String reason) {
        return of(CLOSED, String.valueOf(status), reason);
    }

    /** 沿着原因链找我们自己的错;找不到(网络层、JDK 抛的)按 {@link #UNKNOWN} 带上类型与信息。 */
    public static VoiceError fromError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof VoiceError ve) return ve;
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return of(UNKNOWN, cur.getClass().getSimpleName(), cur.getMessage());
    }

    public String code() {
        return code;
    }

    /** 第 {@code i} 条原话;没有是空串。 */
    public String arg(int i) {
        return i < args.size() ? args.get(i) : "";
    }

    private static List<String> norm(String[] args) {
        String[] out = new String[args.length];
        for (int i = 0; i < args.length; i++) out[i] = args[i] == null ? "" : args[i];
        return List.of(out);
    }
}
