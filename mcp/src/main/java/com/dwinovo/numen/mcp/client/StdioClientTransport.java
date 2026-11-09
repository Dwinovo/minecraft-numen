package com.dwinovo.numen.mcp.client;

import com.dwinovo.numen.mcp.McpThreads;
import com.dwinovo.numen.mcp.protocol.ProtocolVersion;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.dwinovo.numen.mcp.rpc.RpcMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 客户端的 stdio 传输(规范 {@code basic/transports}):服务器是一个子进程,消息一行一条,写进它的标准输入、从它的标准输出读;
 * 它的标准错误是日志,记到调试日志里。关的时候照规范先关标准输入等它自己退出,不退再收掉它和它起的子孙进程
 * (Windows 上 {@code cmd /c npx …} 真正跑的是孙进程)。
 *
 * <p>线程:标准输出与标准错误各一条线程读;写在调用方线程上,一次一条。
 */
public final class StdioClientTransport implements ClientTransport {

    private static final Logger LOG = LoggerFactory.getLogger("Numen");
    /** 关标准输入后等它自己退出、收掉后等它退干净,各等这么久。 */
    private static final long EXIT_WAIT_MS = 2000;

    private final List<String> command;
    private final Map<String, String> env;
    private Process process;
    private OutputStream stdin;
    private volatile boolean closed;

    /** @param env 在继承来的环境变量之上加的 */
    public StdioClientTransport(List<String> command, Map<String, String> env) {
        this.command = List.copyOf(command);
        this.env = Map.copyOf(env);
    }

    @Override
    public void open(Inbound inbound) throws IOException {
        if (process != null) {
            throw new IOException("a stdio server is started once per connection");
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().putAll(env);
        process = builder.start();
        stdin = process.getOutputStream();
        Process started = process;
        McpThreads.start("numen-mcp-stdio-out", () -> {
            try (BufferedReader out = new BufferedReader(new InputStreamReader(started.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    try {
                        RpcMessage.decodeAll(line).forEach(inbound::message);
                    } catch (RpcException malformed) {
                        LOG.warn("[numen-mcp-client] {} wrote a line that is not JSON-RPC: {}", command.get(0), malformed.getMessage());
                    }
                }
            } catch (IOException broken) {
                LOG.debug("[numen-mcp-client] stdout of {} broke: {}", command.get(0), broken.toString());
            }
            inbound.closed(new IOException("the stdio server " + command.get(0) + " exited"));
        });
        McpThreads.start("numen-mcp-stdio-err", () -> {
            try (BufferedReader err = new BufferedReader(new InputStreamReader(started.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    LOG.debug("[numen-mcp-client] {}: {}", command.get(0), line);
                }
            } catch (IOException broken) {
                LOG.debug("[numen-mcp-client] stderr of {} broke: {}", command.get(0), broken.toString());
            }
        });
    }

    @Override
    public synchronized void send(RpcMessage message) throws IOException {
        if (closed) {
            throw new IOException("the transport is closed");
        }
        stdin.write((message.encode() + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    @Override
    public void initialized(ProtocolVersion version) {
        // stdio 没有版本头,也没有另开的流
    }

    @Override
    public void close() {
        Process running;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            running = process;
        }
        if (running == null) {
            return;
        }
        List<ProcessHandle> descendants = running.descendants().toList();
        try {
            running.getOutputStream().close();
            if (!running.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                descendants.forEach(ProcessHandle::destroy);
                running.destroy();
                if (!running.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    descendants.forEach(ProcessHandle::destroyForcibly);
                    running.destroyForcibly();
                }
            }
        } catch (IOException e) {
            running.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.destroyForcibly();
        }
        // 它自己退了,它起的子孙未必跟着退
        descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    }
}
