package com.dwinovo.numen.api.agent.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Non-2xx HTTP response from an LLM endpoint. Carries the status code
 * (for branching: 401 vs 429 vs 5xx), the host that actually answered (after redirects)
 * and the raw response body (for the specific provider error message — DeepSeek 400s in
 * particular contain the actual problem in plain prose).
 *
 * <p>{@link java.io.IOException}-level failures (DNS, connection refused, TLS)
 * are surfaced via the wrapped {@code IOException} on the future, not this class.
 */
public final class LlmHttpException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;
    private final String host;

    public LlmHttpException(int statusCode, String responseBody, String host) {
        super("HTTP " + statusCode + " from " + host + ": " + truncate(responseBody, 500));
        this.statusCode = statusCode;
        this.responseBody = responseBody == null ? "" : responseBody;
        this.host = host == null ? "" : host;
    }

    public int statusCode() { return statusCode; }
    public String responseBody() { return responseBody; }

    /** 回复这次请求的主机名。同一个 key 发到不同的地方,厂家的回话完全不同,所以报错要说清是谁回的。 */
    public String host() { return host; }

    public boolean isRateLimited()    { return statusCode == 429; }
    public boolean isUnauthorized()   { return statusCode == 401 || statusCode == 403; }
    public boolean isClientError()    { return statusCode >= 400 && statusCode < 500; }
    public boolean isServerError()    { return statusCode >= 500; }

    /**
     * 厂家的原话:从常见的错误体里取那句人话(形状见 {@link ProviderReply#of});
     * 不是这些形状就是整个正文。正文为空给空串。
     */
    public String providerMessage() {
        String text = responseBody.strip();
        try {
            JsonElement root = JsonParser.parseString(text);
            String found = root.isJsonObject() ? ProviderReply.of(root.getAsJsonObject()) : null;
            if (found != null) text = found.strip();
        } catch (RuntimeException notJson) {
            // 正文本来就不是 JSON(网关的纯文本错误页之类):原话就是它
        }
        return truncate(text, 300);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "... (truncated)";
    }
}
