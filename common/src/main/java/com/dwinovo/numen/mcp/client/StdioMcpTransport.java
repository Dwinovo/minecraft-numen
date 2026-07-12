package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.Constants;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Stdio transport: spawn the MCP server as a subprocess and speak
 * newline-delimited JSON-RPC over its stdin/stdout. A daemon reader thread
 * correlates responses back to their request by id; stderr is drained to a
 * capped buffer and logged so a server's diagnostics don't get lost or grow
 * unbounded. {@link #close()} kills the subprocess.
 */
public final class StdioMcpTransport implements McpTransport {

    private static final int STDERR_CAP = 64 * 1024;

    private final String name;
    private final Process process;
    private final BufferedWriter writer;
    private final Map<Long, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile Consumer<JsonObject> notificationHandler;

    public StdioMcpTransport(String name, List<String> command, Map<String, String> env) throws IOException {
        this.name = name;
        ProcessBuilder pb = new ProcessBuilder(command);
        if (env != null && !env.isEmpty()) pb.environment().putAll(env);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread reader = new Thread(this::readLoop, "numen-mcp-stdio-" + name);
        reader.setDaemon(true);
        reader.start();
        Thread stderr = new Thread(this::stderrLoop, "numen-mcp-stderr-" + name);
        stderr.setDaemon(true);
        stderr.start();
    }

    @Override
    public CompletableFuture<JsonObject> request(JsonObject frame, long timeoutMs) {
        long id = frame.get("id").getAsLong();
        CompletableFuture<JsonObject> f = new CompletableFuture<>();
        pending.put(id, f);
        f.whenComplete((r, e) -> pending.remove(id));
        try {
            writeLine(frame.toString());
        } catch (IOException ex) {
            f.completeExceptionally(new RuntimeException("stdio write failed: " + ex.getMessage(), ex));
            return f;
        }
        return f.orTimeout(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
    }

    @Override
    public void setNotificationHandler(Consumer<JsonObject> handler) {
        this.notificationHandler = handler;
    }

    @Override
    public void notify(JsonObject frame) {
        try {
            writeLine(frame.toString());
        } catch (IOException ex) {
            Constants.LOG.warn("[numen-mcp-client] '{}' stdio notify failed: {}", name, ex.toString());
        }
    }

    @Override
    public void close() {
        closed = true;
        process.destroy();
        RuntimeException dead = new RuntimeException("stdio transport closed");
        pending.values().forEach(f -> f.completeExceptionally(dead));
        pending.clear();
    }

    private synchronized void writeLine(String line) throws IOException {
        writer.write(line);
        writer.write("\n");
        writer.flush();
    }

    private void readLoop() {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                JsonObject msg;
                try {
                    msg = JsonParser.parseString(line).getAsJsonObject();
                } catch (RuntimeException ex) {
                    continue;   // not a JSON-RPC line (stray stdout) — ignore
                }
                JsonElement idEl = msg.get("id");
                if (idEl == null || idEl.isJsonNull()) {
                    // server → client notification (no id): hand to the sink (e.g. tools/list_changed)
                    Consumer<JsonObject> h = notificationHandler;
                    if (h != null && msg.has("method")) h.accept(msg);
                    continue;
                }
                CompletableFuture<JsonObject> f = pending.remove(idEl.getAsLong());
                if (f != null) f.complete(msg);
            }
        } catch (IOException ex) {
            if (!closed) Constants.LOG.warn("[numen-mcp-client] '{}' stdio reader ended: {}", name, ex.toString());
        }
    }

    private void stderrLoop() {
        StringBuilder buf = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (buf.length() < STDERR_CAP) {
                    buf.append(line).append('\n');
                }
            }
        } catch (IOException ignored) {
            // process gone; fall through to log whatever we captured
        }
        if (buf.length() > 0) {
            Constants.LOG.debug("[numen-mcp-client] '{}' stderr:\n{}", name, buf);
        }
    }
}
