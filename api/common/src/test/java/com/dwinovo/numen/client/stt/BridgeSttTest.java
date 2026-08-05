package com.dwinovo.numen.client.stt;

import com.dwinovo.numen.client.bridge.BridgeDiscovery;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BridgeSttTest {

    @Test
    void sendsCaptureCommandsAndMapsBridgeEventsInOrder() {
        FakeConnector connector = new FakeConnector();
        RecordingListener listener = new RecordingListener();
        BridgeStt backend = new BridgeStt(
                new BridgeDiscovery.Info(URI.create("http://127.0.0.1:38471"), "token"),
                connector);

        SttSession session = backend.open(listener);
        assertTrue(backend.capturesMicrophone());
        assertEquals(List.of("{\"type\":\"capture.start\"}"), connector.socket.sent);

        connector.text("{\"type\":\"capture.started\"}");
        connector.text("{\"type\":\"transcript.delta\",\"transcript\":\"你\"}");
        session.finish();
        connector.text("{\"type\":\"transcript.done\",\"transcript\":\"你好\"}");

        assertEquals(List.of("{\"type\":\"capture.start\"}", "{\"type\":\"capture.stop\"}"),
                connector.socket.sent);
        assertEquals(List.of("started", "partial:你", "final:你好"), listener.events);
        assertTrue(connector.socket.requests >= 4);
    }

    @Test
    void mapsProviderErrorsAndPrematureCloseToFailures() {
        FakeConnector providerConnector = new FakeConnector();
        RecordingListener providerListener = new RecordingListener();
        new BridgeStt(info(), providerConnector).open(providerListener);
        providerConnector.text("{\"type\":\"error\",\"code\":\"provider_error\",\"message\":\"bad key\"}");
        assertInstanceOf(IllegalStateException.class, providerListener.error);
        assertEquals("bad key", providerListener.error.getMessage());

        FakeConnector closeConnector = new FakeConnector();
        RecordingListener closeListener = new RecordingListener();
        new BridgeStt(info(), closeConnector).open(closeListener);
        closeConnector.listener.onClose(closeConnector.socket, 1006, "lost");
        assertInstanceOf(IllegalStateException.class, closeListener.error);
    }

    private static BridgeDiscovery.Info info() {
        return new BridgeDiscovery.Info(URI.create("http://127.0.0.1:38471"), "token");
    }

    private static final class RecordingListener implements SttListener {
        final List<String> events = new ArrayList<>();
        Throwable error;

        @Override public void onCaptureStarted() { events.add("started"); }
        @Override public void onPartial(String text) { events.add("partial:" + text); }
        @Override public void onFinal(String text) { events.add("final:" + text); }
        @Override public void onError(Throwable error) { this.error = error; }
    }

    private static final class FakeConnector implements BridgeStt.WebSocketConnector {
        final FakeWebSocket socket = new FakeWebSocket();
        WebSocket.Listener listener;

        @Override
        public CompletableFuture<WebSocket> connect(URI uri, String authorization, WebSocket.Listener listener) {
            assertEquals("ws://127.0.0.1:38471/v1/audio/capture", uri.toString());
            assertEquals("Bearer token", authorization);
            this.listener = listener;
            listener.onOpen(socket);
            return CompletableFuture.completedFuture(socket);
        }

        void text(String text) {
            listener.onText(socket, text, true);
        }
    }

    private static final class FakeWebSocket implements WebSocket {
        final List<String> sent = new ArrayList<>();
        long requests;
        boolean inputClosed;
        boolean outputClosed;

        @Override public CompletableFuture<WebSocket> sendText(CharSequence data, boolean last) {
            sent.add(data.toString());
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPing(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendPong(ByteBuffer message) {
            return CompletableFuture.completedFuture(this);
        }
        @Override public CompletableFuture<WebSocket> sendClose(int statusCode, String reason) {
            outputClosed = true;
            return CompletableFuture.completedFuture(this);
        }
        @Override public void request(long n) { requests += n; }
        @Override public String getSubprotocol() { return ""; }
        @Override public boolean isOutputClosed() { return outputClosed; }
        @Override public boolean isInputClosed() { return inputClosed; }
        @Override public void abort() { inputClosed = true; outputClosed = true; }
    }
}
