package com.dwinovo.numen.api.skin;

import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 皮肤这一块(查正版档案、MineSkin 代签)的失败:原因码 + 现场的原话。产生错误的那一层只说"是哪一种、对方说了什么",
 * 不写给人看的话;{@link #message()} 把码按收话人的语言翻出来再接上原话(服务端发给玩家的话、客户端画在列表上的话是同一个)。
 * {@link #getMessage()} 是给日志的诊断(码 + 原话,不分语言)。
 *
 * <p>{@code args} 按码约定的顺序:
 * <ul>
 *   <li>{@link #HTTP}:{@code [服务, 状态码, 对方的原话]},原话可为空;</li>
 *   <li>{@link #RATE_LIMITED}:{@code [服务, 对方说多少秒后再来]},秒数可为空;</li>
 *   <li>{@link #REPLY_BAD}、{@link #REPLY_MISSING}、{@link #NETWORK}:{@code [服务]};</li>
 *   <li>{@link #BAD_IMAGE}:{@code [原因]},图片本身不能用(不是 png、尺寸不对、太大、网址不让取);</li>
 *   <li>{@link #NO_SKIN}、{@link #INTERRUPTED}:没有;</li>
 *   <li>{@link #UNKNOWN}:{@code [异常类型, 异常信息]},不是我们认得的错。</li>
 * </ul>
 * 服务名是域名或商标,不翻译。
 */
public final class SkinError extends IllegalStateException {

    public static final String HTTP = "http";
    public static final String RATE_LIMITED = "rate_limited";
    public static final String REPLY_BAD = "reply_bad";
    public static final String REPLY_MISSING = "reply_missing";
    public static final String NETWORK = "network";
    public static final String NO_SKIN = "no_skin";
    public static final String INTERRUPTED = "interrupted";
    public static final String BAD_IMAGE = "bad_image";
    public static final String UNKNOWN = "unknown";

    private final String code;
    private final List<String> args;

    private SkinError(String code, List<String> args, Throwable cause) {
        super(code + (args.isEmpty() ? "" : " " + args), cause);
        this.code = code;
        this.args = args;
    }

    public static SkinError of(String code, String... args) {
        return new SkinError(code, norm(args), null);
    }

    /** 带着底下的异常(网络层抛的)一起抛,日志里能看到原因。 */
    public static SkinError of(Throwable cause, String code, String... args) {
        return new SkinError(code, norm(args), cause);
    }

    public static SkinError http(String service, int status, String reply) {
        return of(HTTP, service, String.valueOf(status), reply);
    }

    /** 对方限流了;{@code retryAfterSeconds} 是它说多久后再来(没说是 0)。 */
    public static SkinError rateLimited(String service, int retryAfterSeconds) {
        return of(RATE_LIMITED, service, retryAfterSeconds > 0 ? String.valueOf(retryAfterSeconds) : "");
    }

    /** 按码和原话重建(服务端把失败推给客户端时只推码和原话,客户端用它翻成当前语言)。 */
    public static SkinError of(String code, List<String> args) {
        return new SkinError(code, List.copyOf(args), null);
    }

    /** 沿着原因链找我们自己的错;找不到按 {@link #UNKNOWN} 带上类型与信息。 */
    public static SkinError fromError(Throwable error) {
        Throwable cur = error;
        while (cur != null) {
            if (cur instanceof SkinError se) return se;
            if (cur.getCause() == null || cur.getCause() == cur) break;
            cur = cur.getCause();
        }
        return of(UNKNOWN, cur.getClass().getSimpleName(), cur.getMessage());
    }

    public String code() {
        return code;
    }

    public List<String> args() {
        return args;
    }

    /** 对方限流时说的"多少秒后再来";没说(或不是限流)是 0。 */
    public int retryAfterSeconds() {
        try {
            return code.equals(RATE_LIMITED) ? Math.max(0, Integer.parseInt(arg(1))) : 0;
        } catch (NumberFormatException none) {
            return 0;
        }
    }

    /** 给人看的话:语言文件里 {@code numen.skin.error.<码>},对方有原话的接在后面。 */
    public Component message() {
        Component base = Component.translatable("numen.skin.error." + code, arg(0), arg(1));
        String reply = code.equals(HTTP) ? arg(2) : "";
        return reply.isBlank() ? base : Component.translatable("numen.skin.error.detail", base, reply);
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
