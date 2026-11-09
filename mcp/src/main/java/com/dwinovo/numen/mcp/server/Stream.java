package com.dwinovo.numen.mcp.server;

import com.dwinovo.numen.mcp.rpc.RpcMessage;
import com.dwinovo.numen.mcp.rpc.RpcPeer;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 发往客户端的一条 HTTP 流(一个 POST 的答复,或客户端开的 GET 流)的出口。谁都可以往里放消息({@link #send} 只入队、从不阻塞),
 * 只有占着这个 HTTP 请求的那条线程往外写({@link #next}):游戏主线程报进度、问人时不碰套接字,对方收得慢也堵不到它。
 */
final class Stream implements RpcPeer.Outbound {

    /** {@link #next} 交出它表示:这条流收场了(关了,或在等的那个请求被撤回了,见 {@link Dropped})。 */
    static final RpcMessage.Notification CLOSED = new RpcMessage.Notification("", null);

    /** {@link #next} 交出它表示:这个请求被撤回,不会有答复了。 */
    record Dropped(JsonPrimitive id) {}

    private final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
    private volatile boolean closed;

    @Override
    public void send(RpcMessage message) throws IOException {
        if (closed) {
            throw new IOException("the stream is closed");
        }
        queue.add(message);
    }

    @Override
    public void dropped(JsonPrimitive requestId) {
        queue.add(new Dropped(requestId));
    }

    /**
     * 下一样要写的:一条消息、一个 {@link Dropped},流关了是 {@link #CLOSED};等 {@code timeoutMs} 还没有是 null。
     */
    Object next(long timeoutMs) throws InterruptedException {
        if (closed && queue.isEmpty()) {
            return CLOSED;
        }
        Object next = queue.poll(timeoutMs, TimeUnit.MILLISECONDS);
        return next == null && closed ? CLOSED : next;
    }

    /** 关掉:之后的 {@link #send} 抛出,写的那条线程写完已在队里的就收场。 */
    void close() {
        closed = true;
        queue.add(CLOSED);
    }

    boolean closed() {
        return closed;
    }
}
