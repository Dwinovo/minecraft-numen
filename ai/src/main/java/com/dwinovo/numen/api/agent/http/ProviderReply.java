package com.dwinovo.numen.api.agent.http;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 厂家错误话的形状只在这里认:HTTP 错误体与流里的错误帧用的是同一批写法。
 */
public final class ProviderReply {

    private ProviderReply() {}

    /**
     * 从错误体或错误帧里取那句人话——{@code {"error":{"message":…}}}(OpenAI、OpenRouter、DeepSeek、Anthropic)、
     * {@code {"error":"…"}}、{@code {"message":…}}、{@code {"detail":…}};都不是给 {@code null}。
     */
    public static String of(JsonObject o) {
        for (String key : new String[]{"error", "message", "detail"}) {
            JsonElement v = o.get(key);
            if (v == null) continue;
            if (v.isJsonPrimitive()) return v.getAsString();
            if (v.isJsonObject()) {
                String inner = of(v.getAsJsonObject());
                if (inner != null) return inner;
            }
        }
        return null;
    }
}
