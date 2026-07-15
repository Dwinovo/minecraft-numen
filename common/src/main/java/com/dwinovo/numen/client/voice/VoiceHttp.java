package com.dwinovo.numen.client.voice;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 语音管线共用的 {@link HttpClient}。TTS 请求不复用 LLM 的
 * {@code HttpLlmTransport}（那边带 SSE 看门狗与重试语义,对二进制响应不适用），
 * 但同样只用 JDK 内置 HttpClient,零第三方依赖。
 */
final class VoiceHttp {

    /** 单句合成的整体超时——TTS 一句话正常几百毫秒到几秒,30s 已经极限。 */
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private VoiceHttp() {}
}
