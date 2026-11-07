package com.dwinovo.numen.api.agent.http;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 报错带上谁回的、说了什么原话:各家错误体的形状都能取出那句人话。 */
class LlmHttpExceptionTest {

    private static String reply(String body) {
        return new LlmHttpException(401, body, "h").providerMessage();
    }

    @Test
    void theCommonErrorShapesYieldTheProvidersOwnSentence() {
        // OpenRouter、OpenAI、DeepSeek:error.message
        assertEquals("User not found.", reply("{\"error\":{\"message\":\"User not found.\",\"code\":401}}"));
        assertEquals("Incorrect API key provided: sk-***.",
                reply("{\"error\":{\"message\":\"Incorrect API key provided: sk-***.\",\"type\":\"invalid_request_error\","
                        + "\"code\":\"invalid_api_key\"}}"));
        // Anthropic
        assertEquals("invalid x-api-key",
                reply("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}"));
        // 其余常见形状
        assertEquals("Missing Authentication header", reply("{\"error\":\"Missing Authentication header\"}"));
        assertEquals("quota exceeded", reply("{\"message\":\"quota exceeded\"}"));
        assertEquals("bad token", reply("{\"detail\":\"bad token\"}"));
    }

    @Test
    void anythingElseIsTheBodyItself() {
        assertEquals("<html>502 Bad Gateway</html>", reply("<html>502 Bad Gateway</html>\n"));
        assertEquals("{\"x\":1}", reply("{\"x\":1}"));
        assertEquals("", reply(""));
        assertEquals("", reply(null));
    }

    @Test
    void aVeryLongBodyIsCut() {
        assertTrue(reply("x".repeat(5000)).length() < 400);
    }

    @Test
    void theMessageNamesTheHost() {
        assertTrue(new LlmHttpException(401, "{}", "openrouter.ai").getMessage().contains("openrouter.ai"));
        assertEquals("openrouter.ai", new LlmHttpException(401, "{}", "openrouter.ai").host());
    }

    @Test
    void theHostComesFromTheServerThatActuallyAnswered() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            byte[] body = "{\"error\":{\"message\":\"User not found.\"}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(401, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        try {
            var transport = new HttpLlmTransport("", java.util.Map.of());
            var failure = assertThrows(CompletionException.class, () -> transport.post(
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions", "k",
                    new com.google.gson.JsonObject()).join());
            LlmHttpException http = assertInstanceOf(LlmHttpException.class, failure.getCause());
            assertEquals("127.0.0.1", http.host());
            assertEquals("User not found.", http.providerMessage());
        } finally {
            server.stop(0);
        }
    }
}
