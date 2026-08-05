package com.dwinovo.numen.client.voice;

import com.dwinovo.numen.client.bridge.BridgeDiscovery;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BridgeTtsTest {
    private HttpServer server;
    private AtomicReference<String> authorization;
    private AtomicReference<String> requestBody;

    @BeforeEach
    void setUp() throws IOException {
        authorization = new AtomicReference<>();
        requestBody = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void sendsAuthenticatedOpenAiSpeechRequestAndReturnsWav() throws Exception {
        server.createContext("/v1/audio/speech", exchange -> {
            capture(exchange);
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            respond(exchange, 200, new byte[]{'R', 'I', 'F', 'F'});
        });

        byte[] result = backend().synthesize("你好").get();

        assertArrayEquals(new byte[]{'R', 'I', 'F', 'F'}, result);
        assertEquals("Bearer bridge-token", authorization.get());
        JsonObject body = JsonParser.parseString(requestBody.get()).getAsJsonObject();
        assertEquals("qwen3-tts-flash-realtime", body.get("model").getAsString());
        assertEquals("Cherry", body.get("voice").getAsString());
        assertEquals("你好", body.get("input").getAsString());
        assertEquals("wav", body.get("response_format").getAsString());
    }

    @Test
    void rejectsUnauthorizedEmptyAndTimedOutResponses() {
        server.createContext("/v1/audio/speech", exchange -> respond(exchange, 401,
                "bad token".getBytes(StandardCharsets.UTF_8)));
        ExecutionException unauthorized = assertThrows(ExecutionException.class,
                () -> backend().synthesize("x").get());
        assertInstanceOf(IllegalStateException.class, unauthorized.getCause());

        server.removeContext("/v1/audio/speech");
        server.createContext("/v1/audio/speech", exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            respond(exchange, 200, new byte[0]);
        });
        ExecutionException empty = assertThrows(ExecutionException.class,
                () -> backend().synthesize("x").get());
        assertInstanceOf(IllegalStateException.class, empty.getCause());

        server.removeContext("/v1/audio/speech");
        server.createContext("/v1/audio/speech", exchange -> {
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, new byte[]{1});
        });
        ExecutionException timedOut = assertThrows(ExecutionException.class,
                () -> new BridgeTts(info(), "m", "v", HttpClient.newHttpClient(), Duration.ofMillis(30))
                        .synthesize("x").get());
        assertInstanceOf(Exception.class, timedOut.getCause());
    }

    private BridgeTts backend() {
        return new BridgeTts(info(), "qwen3-tts-flash-realtime", "Cherry");
    }

    private BridgeDiscovery.Info info() {
        return new BridgeDiscovery.Info(
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "bridge-token");
    }

    private void capture(HttpExchange exchange) throws IOException {
        authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
