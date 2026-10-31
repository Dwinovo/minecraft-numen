package com.dwinovo.numen.api.agent.loop;

import com.dwinovo.numen.api.agent.http.LlmHttpException;
import com.dwinovo.numen.api.agent.http.LlmStreamException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 一次模型调用抛出来的错归成哪个原因码。 */
class CauseTest {

    @Test
    void aStreamThatFailedAfterItStartedCarriesTheVendorsWords() {
        Cause cause = Cause.fromError(new CompletionException(new LlmStreamException("server_error: overloaded")));

        assertEquals(Cause.of(Cause.STREAM_FAILED, "server_error: overloaded"), cause);
        assertEquals("回应中途出错:server_error: overloaded", cause.forModel());
    }

    /** 服务商说上下文超长(各家措辞不同,或是 413)是一个独立的原因码,内核据此整理再试;限流字眼像超长也不算。 */
    @Test
    void aProviderSayingTheContextIsTooLongIsItsOwnCause() {
        for (String body : new String[] {
                "{\"error\":{\"message\":\"prompt is too long: 213462 tokens > 200000 maximum\"}}",
                "{\"error\":{\"message\":\"Your input exceeds the context window of this model\"}}",
                "{\"error\":{\"message\":\"This endpoint's maximum context length is 131072 tokens.\"}}",
                "Input length (265330) exceeds model's maximum context length (262144)."}) {
            Cause cause = Cause.fromError(new CompletionException(new LlmHttpException(400, body, "api.example")));
            assertEquals(Cause.CONTEXT_OVERFLOW, cause.code(), body);
        }
        assertEquals(Cause.CONTEXT_OVERFLOW,
                Cause.fromError(new LlmHttpException(413, "", "api.example")).code(), "413 一律是请求太大");
        assertEquals(Cause.RATE_LIMITED, Cause.fromError(new LlmHttpException(429,
                "{\"error\":{\"message\":\"Rate limit reached: too many tokens per minute\"}}", "api.openai.com")).code());
        assertEquals(Cause.BAD_REQUEST, Cause.fromError(new LlmHttpException(400,
                "{\"error\":{\"message\":\"temperature is not supported\"}}", "api.example")).code());
    }

    @Test
    void anHttpRefusalStillSortsByStatus() {
        Cause cause = Cause.fromError(new CompletionException(new LlmHttpException(429, "{}", "api.openai.com")));

        assertEquals(Cause.RATE_LIMITED, cause.code());
    }
}
