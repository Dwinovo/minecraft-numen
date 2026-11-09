package com.dwinovo.numen.mcp.rpc;

import com.dwinovo.numen.mcp.McpThreads;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON-RPC 的一端:编号与答复配对、对方的请求怎么答、撤回两个方向、超时撤回;以及消息的编解码。 */
class RpcPeerTest {

    /** 两端用一条队列对接:一端发的消息进队,由用例交给另一端。 */
    private static final class Wire implements RpcPeer.Outbound {
        final BlockingQueue<Object> sent = new LinkedBlockingQueue<>();

        @Override
        public void send(RpcMessage message) {
            sent.add(message);
        }

        @Override
        public void dropped(JsonPrimitive requestId) {
            sent.add("dropped " + requestId);
        }

        Object next() throws InterruptedException {
            return sent.poll(10, TimeUnit.SECONDS);
        }
    }

    private static RpcPeer peer() {
        return new RpcPeer(McpThreads.perTask("test-peer-"));
    }

    @Test
    void aRequestIsAnsweredByItsHandlerAndTheAnswerMatchesTheId() throws Exception {
        RpcPeer server = peer();
        server.onRequest("echo", request -> request.params());
        RpcPeer client = peer();
        Wire toServer = new Wire();
        Wire toClient = new Wire();

        RpcPeer.Outgoing outgoing = client.request("echo", params("x", "1"), toServer);
        server.receive((RpcMessage) toServer.next(), toClient);
        client.receive((RpcMessage) toClient.next(), toServer);

        assertEquals("{\"x\":\"1\"}", outgoing.result().get(1, TimeUnit.SECONDS).toString());
        assertEquals(0, client.pending());
    }

    @Test
    void anAnswerReadOffTheWireFindsTheRequestItAnswers() throws Exception {
        RpcPeer client = peer();
        Wire toServer = new Wire();
        RpcPeer.Outgoing outgoing = client.request("x", null, toServer);

        // 对方答复里的编号是读出来的数字(Gson 的数字与我们造的 long 哈希不同),照样认得回去
        client.receive(RpcMessage.decode("{\"jsonrpc\":\"2.0\",\"id\":" + outgoing.id() + ",\"result\":{\"ok\":true}}"), toServer);

        assertEquals("{\"ok\":true}", outgoing.result().get(1, TimeUnit.SECONDS).toString());
    }

    @Test
    void anUnknownMethodAndAThrowingHandlerAreJsonRpcErrors() throws Exception {
        RpcPeer server = peer();
        server.onRequest("boom", request -> { throw new IllegalStateException("bad"); });
        server.onRequest("refuse", request -> { throw new RpcException(RpcError.INVALID_PARAMS, "no"); });
        Wire out = new Wire();

        server.receive(new RpcMessage.Request(new JsonPrimitive(1), "nope", null), out);
        server.receive(new RpcMessage.Request(new JsonPrimitive(2), "boom", null), out);
        server.receive(new RpcMessage.Request(new JsonPrimitive(3), "refuse", null), out);

        List<Integer> codes = List.of(code(out.next()), code(out.next()), code(out.next()));
        assertTrue(codes.containsAll(List.of(RpcError.METHOD_NOT_FOUND, RpcError.INTERNAL_ERROR, RpcError.INVALID_PARAMS)),
                codes.toString());
    }

    private static int code(Object message) {
        return ((RpcMessage.Response) message).error().code();
    }

    @Test
    void aRequestTheOtherSideCancelsIsInterruptedAndNotAnswered() throws Exception {
        RpcPeer server = peer();
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        server.onRequest("slow", request -> {
            started.countDown();
            try {
                Thread.sleep(20_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
                throw e;
            }
            return new JsonObject();
        });
        Wire out = new Wire();
        server.receive(new RpcMessage.Request(new JsonPrimitive(7), "slow", null), out);
        assertTrue(started.await(10, TimeUnit.SECONDS));

        server.receive(new RpcMessage.Notification(RpcPeer.CANCELLED, params("requestId", 7)), out);

        assertEquals("dropped 7", out.next(), "撤回的请求不答复,只告诉那条路它收场了");
        assertTrue(interrupted.get());
    }

    @Test
    void initializeCannotBeCancelled() throws Exception {
        RpcPeer server = peer();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.onRequest("initialize", request -> {
            started.countDown();
            release.await();
            return new JsonObject();
        });
        Wire out = new Wire();
        server.receive(new RpcMessage.Request(new JsonPrimitive(1), "initialize", null), out);
        assertTrue(started.await(10, TimeUnit.SECONDS));

        server.receive(new RpcMessage.Notification(RpcPeer.CANCELLED, params("requestId", 1)), out);
        release.countDown();

        assertInstanceOf(RpcMessage.Response.class, out.next(), "规范:initialize 不许撤回");
    }

    @Test
    void aCallThatTimesOutIsWithdrawnWithACancellation() throws Exception {
        RpcPeer client = peer();
        Wire toServer = new Wire();

        assertThrows(TimeoutException.class, () -> client.call("slow", new JsonObject(), toServer, Duration.ofMillis(200)));

        RpcMessage.Request request = (RpcMessage.Request) toServer.next();
        RpcMessage.Notification cancel = (RpcMessage.Notification) toServer.next();
        assertEquals(RpcPeer.CANCELLED, cancel.method());
        assertEquals(request.id(), cancel.params().get("requestId"));
        assertEquals(0, client.pending());
        client.receive(RpcMessage.Response.ok(request.id(), new JsonObject()), toServer);   // 晚到的答复不理
        assertNull(toServer.sent.poll());
    }

    @Test
    void anAnswerWithAnErrorFailsTheCallWithThatError() throws Exception {
        RpcPeer client = peer();
        Wire toServer = new Wire();
        RpcPeer.Outgoing outgoing = client.request("x", null, toServer);

        client.receive(RpcMessage.Response.failed(outgoing.id(), new RpcError(-32602, "bad params")), toServer);

        Exception failed = assertThrows(Exception.class, () -> outgoing.result().get(1, TimeUnit.SECONDS));
        assertEquals(-32602, ((RpcException) failed.getCause()).error().code());
    }

    @Test
    void aRequestThatCannotBeSentFailsAtOnceAndTheLostOneFailsWhenToldSo() throws Exception {
        RpcPeer client = peer();
        RpcPeer.Outgoing unsent = client.request("x", null, message -> { throw new IOException("gone"); });
        assertTrue(unsent.result().isCompletedExceptionally());

        RpcPeer.Outgoing lost = client.request("y", null, new Wire());
        client.fail(lost.id(), new IOException("stream broke"));
        assertTrue(lost.result().isCompletedExceptionally());
        assertEquals(0, client.pending());
    }

    // ---- 编解码 ----

    @Test
    void messagesRoundTripAndBadInputIsAParseOrInvalidRequestError() throws Exception {
        RpcMessage.Request request = new RpcMessage.Request(new JsonPrimitive("a-1"), "tools/list", params("cursor", "c"));
        assertEquals(request, RpcMessage.decode(request.encode()));
        RpcMessage.Response response = RpcMessage.Response.failed(new JsonPrimitive(3), new RpcError(-32601, "nope"));
        assertEquals(response.toJson(), RpcMessage.decode(response.encode()).toJson());

        assertEquals(RpcError.PARSE_ERROR, assertThrows(RpcException.class, () -> RpcMessage.decode("{oops")).error().code());
        assertEquals(RpcError.INVALID_REQUEST, assertThrows(RpcException.class,
                () -> RpcMessage.decode("{\"jsonrpc\":\"1.0\",\"method\":\"x\"}")).error().code());
        assertEquals(RpcError.INVALID_REQUEST, assertThrows(RpcException.class,
                () -> RpcMessage.decode("[{\"jsonrpc\":\"2.0\",\"method\":\"x\"}]")).error().code(), "一条消息的地方不收数组");
        assertEquals(2, RpcMessage.decodeAll("[{\"jsonrpc\":\"2.0\",\"method\":\"x\"},{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}]")
                .size(), "成批的拆成一条条");
    }

    private static JsonObject params(String key, Object value) {
        JsonObject o = new JsonObject();
        if (value instanceof Number n) {
            o.addProperty(key, n);
        } else {
            o.addProperty(key, String.valueOf(value));
        }
        return o;
    }
}
