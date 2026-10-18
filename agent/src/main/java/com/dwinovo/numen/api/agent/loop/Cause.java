package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.http.LlmHttpException;
import com.dwinovo.numen.api.agent.http.LlmStreamException;

import java.util.List;

/**
 * 一件事为什么没成,结构化地记:原因码 + 现场的原话。内核、日志、事件里传的都是它,不传一句已经翻好的话——
 * 话是给人看的,哪种语言只有界面知道(界面把码翻成当前语言,再接上原话);给模型看的那句另走 {@link #forModel()},
 * 两句各自从码和原话生成,互不拼接。
 *
 * <p>码的定义只在这里(产生它的模块),界面的文案只在界面的语言文件里。{@code args} 是原话,顺序按码约定:
 * <ul>
 *   <li>{@code llm.*}(调用没成):{@code [HTTP 状态码(没有是 "0"), 回话的主机, 厂家的原话]},后两项可为空;
 *       {@code llm.stream_failed} 例外,只有 {@code [原话]};</li>
 *   <li>{@code endpoint.no_key}、{@code endpoint.no_url}:{@code [档案的名字]};{@code endpoint.unbound}、{@code endpoint.quota} 没有;</li>
 *   <li>{@code halt.*}(切断,见 {@link HaltReason}):死因这类现场的话,没有就空。</li>
 * </ul>
 *
 * @param code 原因码
 * @param args 现场的原话,按码约定的顺序
 */
public record Cause(String code, List<String> args) {

    public Cause {
        args = List.copyOf(args);
    }

    // ---- 模型调用没成 ----
    public static final String UNAUTHORIZED = "llm.unauthorized";
    public static final String NOT_FOUND = "llm.not_found";
    public static final String RATE_LIMITED = "llm.rate_limited";
    public static final String SERVER_ERROR = "llm.server_error";
    public static final String BAD_REQUEST = "llm.bad_request";
    /** 连不上:没有任何回话。 */
    public static final String NETWORK = "llm.network";
    /** 连接成功、回应开始了,流里却带着错误或没有收尾({@link LlmStreamException}):原话是厂家在流里说的。 */
    public static final String STREAM_FAILED = "llm.stream_failed";
    /** 服务端回了,但回应是空的(没有话也没有工具调用)。 */
    public static final String EMPTY_REPLY = "llm.empty_reply";

    // ---- 发不了请求(停在 BLOCKED) ----
    public static final String UNBOUND = "endpoint.unbound";
    public static final String NO_KEY = "endpoint.no_key";
    public static final String NO_URL = "endpoint.no_url";
    /** 档案用的是服主的 key,而这位玩家今天用服主的 key 的额度用完了。 */
    public static final String QUOTA = "endpoint.quota";

    /**
     * 一次模型调用抛出来的错归成哪个码:有 HTTP 回话的按状态码分,没有回话(连不上、超时)是 {@link #NETWORK}。
     * 状态码到码的对应只在这一处。
     */
    public static Cause fromError(Throwable error) {
        Throwable cause = error;
        while (cause != null && !(cause instanceof LlmHttpException) && !(cause instanceof LlmStreamException)
                && cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof LlmStreamException stream) {
            return of(STREAM_FAILED, stream.getMessage());
        }
        if (cause instanceof LlmHttpException http) {
            String code = http.isUnauthorized() ? UNAUTHORIZED
                    : http.statusCode() == 404 ? NOT_FOUND
                    : http.isRateLimited() ? RATE_LIMITED
                    : http.statusCode() >= 500 ? SERVER_ERROR
                    : BAD_REQUEST;
            return llm(code, http.statusCode(), http.host(), http.providerMessage());
        }
        return of(NETWORK);
    }

    /** 只有码、没有原话。 */
    public static Cause of(String code) {
        return new Cause(code, List.of());
    }

    /** 带一句原话(档案名、死因)。 */
    public static Cause of(String code, String detail) {
        return new Cause(code, List.of(detail == null ? "" : detail));
    }

    /** 调用没成:状态码、回话的主机、厂家的原话(都可以没有)。 */
    public static Cause llm(String code, int status, String host, String reply) {
        return new Cause(code, List.of(String.valueOf(status), host == null ? "" : host, reply == null ? "" : reply));
    }

    /** 第 {@code i} 条原话;没有是空串。 */
    public String arg(int i) {
        return i < args.size() ? args.get(i) : "";
    }

    /**
     * 给模型看的那句:这一次为什么没成。从码和原话直接生成,不经过给人看的文案。
     * 日志也用它(日志不分语言)。
     */
    public String forModel() {
        String what = switch (code) {
            case UNAUTHORIZED -> "密钥无效";
            case NOT_FOUND -> "地址不对或型号不存在";
            case RATE_LIMITED -> "被限流了";
            case SERVER_ERROR -> "服务端出错了";
            case BAD_REQUEST -> "请求被拒绝(HTTP " + arg(0) + ")";
            case NETWORK -> "连不上";
            case STREAM_FAILED -> "回应中途出错:" + arg(0);
            case EMPTY_REPLY -> "服务端返回了空回应";
            case UNBOUND -> "没绑模型配置";
            case NO_KEY -> "模型配置没填密钥";
            case NO_URL -> "模型配置没有地址";
            case QUOTA -> "服主提供的模型额度今天用完了";
            default -> code;
        };
        if (code.startsWith("llm.") && !arg(2).isEmpty()) {
            return what + " · " + arg(1) + " 回复:" + arg(2);
        }
        return what;
    }
}
