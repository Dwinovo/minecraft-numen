package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.client.bridge.BridgeDiscovery;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** STT adapter for the local Numen Bridge microphone capture socket. */
public final class BridgeStt implements SttBackend {

    public static final String BACKEND = "bridge";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @FunctionalInterface
    public interface WebSocketConnector {
        CompletableFuture<WebSocket> connect(URI uri, String authorization, WebSocket.Listener listener);
    }

    private final BridgeDiscovery.Info discovery;
    private final WebSocketConnector connector;

    public BridgeStt(BridgeDiscovery.Info discovery) {
        this(discovery, (uri, authorization, listener) -> CLIENT.newWebSocketBuilder()
                .header("Authorization", authorization)
                .connectTimeout(Duration.ofSeconds(5))
                .buildAsync(uri, listener));
    }

    public BridgeStt(BridgeDiscovery.Info discovery, WebSocketConnector connector) {
        this.discovery = discovery;
        this.connector = connector;
    }

    @Override
    public SttSession open(SttListener listener) {
        RealtimeSession session = new RealtimeSession(listener);
        session.connect();
        return session;
    }

    @Override
    public String describe() {
        return "numen-bridge(" + discovery.baseUrl() + ")";
    }

    @Override
    public boolean capturesMicrophone() {
        return true;
    }

    private final class RealtimeSession implements SttSession {
        private final SttListener listener;
        private final AtomicBoolean done = new AtomicBoolean(false);
        private volatile WebSocket socket;
        private volatile boolean finishRequested;

        RealtimeSession(SttListener listener) {
            this.listener = listener;
        }

        void connect() {
            try {
                connector.connect(discovery.captureUri(), "Bearer " + discovery.token(), new Listener())
                        .whenComplete((ws, error) -> {
                            if (error != null && done.compareAndSet(false, true)) {
                                listener.onError(unwrap(error));
                            }
                        });
            } catch (RuntimeException error) {
                if (done.compareAndSet(false, true)) listener.onError(error);
            }
        }

        @Override
        public void feed(byte[] pcm) {
            // Bridge owns the microphone; Java Sound PCM is intentionally ignored.
        }

        @Override
        public void finish() {
            finishRequested = true;
            WebSocket ws = socket;
            if (ws != null && !done.get()) sendStop(ws);
        }

        @Override
        public void cancel() {
            if (done.compareAndSet(false, true)) {
                WebSocket ws = socket;
                if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "cancel");
            }
        }

        private void sendStart(WebSocket ws) {
            ws.sendText("{\"type\":\"capture.start\"}", true);
        }

        private void sendStop(WebSocket ws) {
            ws.sendText("{\"type\":\"capture.stop\"}", true);
        }

        private final class Listener implements WebSocket.Listener {
            private final StringBuilder fragments = new StringBuilder();

            @Override
            public void onOpen(WebSocket ws) {
                socket = ws;
                sendStart(ws);
                if (finishRequested) sendStop(ws);
                ws.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                fragments.append(data);
                if (last) {
                    try {
                        handle(fragments.toString());
                    } catch (RuntimeException error) {
                        if (done.compareAndSet(false, true)) listener.onError(error);
                    } finally {
                        fragments.setLength(0);
                    }
                }
                ws.request(1);
                return CompletableFuture.completedFuture(null);
            }

            void handle(String text) {
                JsonObject event = JsonParser.parseString(text).getAsJsonObject();
                String type = event.has("type") ? event.get("type").getAsString() : "";
                switch (type) {
                    case "capture.started" -> listener.onCaptureStarted();
                    case "transcript.delta" -> listener.onPartial(string(event, "transcript"));
                    case "transcript.done" -> {
                        if (done.compareAndSet(false, true)) {
                            listener.onFinal(string(event, "transcript").strip());
                            closeQuietly();
                        }
                    }
                    case "error" -> {
                        if (done.compareAndSet(false, true)) {
                            listener.onError(new IllegalStateException(string(event, "message")));
                            closeQuietly();
                        }
                    }
                    default -> { }
                }
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                if (done.compareAndSet(false, true)) listener.onError(error);
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
                if (done.compareAndSet(false, true)) {
                    listener.onError(new IllegalStateException("Numen Bridge connection closed: " + reason));
                }
                return CompletableFuture.completedFuture(null);
            }

            private void closeQuietly() {
                WebSocket ws = socket;
                if (ws != null) ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }

            private String string(JsonObject object, String key) {
                return object.has(key) ? object.get(key).getAsString() : "";
            }
        }

        private Throwable unwrap(Throwable error) {
            return error.getCause() == null ? error : error.getCause();
        }
    }
}
