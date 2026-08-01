package com.dwinovo.numen.client.voice;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 语音管线共用的 {@link HttpClient}，以及各后端共用的 URL 卫生工具。TTS 请求不复用
 * LLM 的 {@code HttpLlmTransport}（那边带 SSE 看门狗与重试语义,对二进制响应不适用），
 * 但同样只用 JDK 内置 HttpClient,零第三方依赖。
 */
final class VoiceHttp {

    /** 单句合成的整体超时——TTS 一句话正常几百毫秒到几秒,30s 已经极限。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /**
     * 用户填的 base URL 缺 scheme 时补上:本机/内网地址补 {@code http}（本地 TTS
     * 服务基本不走 TLS），其余补 {@code https}。空串原样返回,由 {@link #uriOf}
     * 给出人话报错。裸域名曾让 {@code HttpRequest.uri()} 在渲染线程抛
     * "URI with undefined scheme" 直接把游戏崩掉。
     */
    static String ensureScheme(String base) {
        String b = base == null ? "" : base.strip();
        if (b.isEmpty() || b.contains("://")) return b;
        String host = b.split("[/:]", 2)[0];
        boolean local = host.equals("localhost") || host.startsWith("127.")
                || host.startsWith("192.168.") || host.startsWith("10.");
        return (local ? "http://" : "https://") + b;
    }

    /**
     * 拼好的请求 URL → {@link URI}。空/无 host 时抛人话 {@link IllegalStateException}
     * ——各后端的 {@code synthesize} 把它接成 failedFuture 走统一的失败通道
     * （表单红字/语音跳过），绝不允许同步炸出去。
     */
    static URI uriOf(String url) {
        String u = url == null ? "" : url.strip();
        if (u.isEmpty() || u.startsWith("/")) {
            throw new IllegalStateException("TTS URL 未填写");
        }
        URI uri = URI.create(u);
        if (uri.getHost() == null || uri.getScheme() == null) {
            throw new IllegalStateException("TTS URL 无效: " + u);
        }
        return uri;
    }

    /**
     * HTTP 错误体 → 人话:响应是 JSON 时挖出 {@code message}/{@code msg}/{@code detail}/
     * {@code error(.message)} 字段直接展示(如 Fish 402 的 "Insufficient API credit…"),
     * 挖不到才退回截断的原文——表单红字放不下一坨 JSON。
     */
    static String humanHttpError(String provider, int status, String body) {
        return provider + " HTTP " + status + ": " + extractMessage(body);
    }

    private static String extractMessage(String body) {
        String raw = body == null ? "" : body.strip();
        try {
            var el = com.google.gson.JsonParser.parseString(raw);
            if (el.isJsonObject()) {
                var o = el.getAsJsonObject();
                for (String k : new String[]{"message", "msg", "detail", "error_msg"}) {
                    if (o.has(k) && o.get(k).isJsonPrimitive()) {
                        return o.get(k).getAsString();
                    }
                }
                if (o.has("error")) {
                    var err = o.get("error");
                    if (err.isJsonObject() && err.getAsJsonObject().has("message")) {
                        return err.getAsJsonObject().get("message").getAsString();
                    }
                    if (err.isJsonPrimitive()) {
                        return err.getAsString();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 不是 JSON——用原文
        }
        return raw.length() > 300 ? raw.substring(0, 300) + "..." : raw;
    }

    private VoiceHttp() {}
}
