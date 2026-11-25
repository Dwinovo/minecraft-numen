package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.SdkFixture;
import com.dwinovo.numen.script.ApiReply;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 反向请求走座位:程序里的客户端函数经 {@code client/call} 到她的座位上坐着的客户端,答复回来;撤回、断线、装不下、没人坐,
 * 都是这次调用的一条失败。客户端的主线程在这里是一个队列,测试决定什么时候执行。
 */
@Tag("mc")
class SeatTransportTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();

    /** 夹具:只有主人客户端答得了的函数。 */
    public static final class Fns {

        private Fns() {}

        public record N(@Doc("A number.") int n) {}

        public record Text(@Doc("A text.") String text) {}

        @Fn("Double, on the owner's client.")
        @Example("gt.gt_seat.twice(2)")
        public static int twice(ClientCall call, N args) {
            return args.n() * 2;
        }

        @Fn("A text of the given length, on the owner's client.")
        @Example("gt.gt_seat.big(5)")
        public static String big(ClientCall call, N args) {
            return "z".repeat(args.n());
        }

        @Fn("How long the text is, on the owner's client.")
        @Example("gt.gt_seat.length(\"abc\")")
        public static int length(ClientCall call, Text args) {
            return args.text().length();
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_seat", Fns.class);
    }

    private StubSeatWorld world;
    private Seats seats;
    private SeatPipe pipe;
    /** 客户端主线程上排着的活。 */
    private final Queue<Runnable> clientMain = new ConcurrentLinkedQueue<>();

    @BeforeEach
    void wire() throws Exception {
        world = new StubSeatWorld().createdBy(HER, OWNER);
        seats = new Seats(world, THREADS);
        pipe = SeatPipe.connect(seats, OWNER, clientMain::add, THREADS);
        pipe.client().open(HER, 0).get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void unplug() {
        seats.clear();
    }

    private void runClientMain() {
        for (Runnable task; (task = clientMain.poll()) != null; ) {
            task.run();
        }
    }

    private static ClientTransport.Request request(String callId, String function, String json) {
        return new ClientTransport.Request(HER, callId, function,
                com.google.gson.JsonParser.parseString(json).getAsJsonObject());
    }

    private CompletableFuture<String> ask(String callId, String function, String json) {
        CompletableFuture<String> answer = new CompletableFuture<>();
        seats.transport().request(request(callId, function, json), answer::complete);
        return answer;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("it did not happen in ten seconds");
            }
            Thread.sleep(2);
        }
    }

    @Test
    void aClientFunctionIsAnsweredByTheClientThatSitsAtHerSeat() throws Exception {
        CompletableFuture<String> answer = ask("p#1", "gt.gt_seat.twice", "{\"n\":21}");
        awaitTrue(() -> !clientMain.isEmpty());   // 请求到了客户端,在它的主线程上排着
        assertFalse(answer.isDone());
        runClientMain();
        JsonObject reply = com.google.gson.JsonParser.parseString(answer.get(10, TimeUnit.SECONDS)).getAsJsonObject();
        assertEquals(ApiReply.value(new com.google.gson.JsonPrimitive(42)), reply);
    }

    @Test
    void aCancelledCallIsNeverAnsweredToTheProgramEvenIfTheClientAnswersLate() throws Exception {
        CompletableFuture<String> withdrawn = ask("p#1", "gt.gt_seat.twice", "{\"n\":1}");
        awaitTrue(() -> !clientMain.isEmpty());
        seats.transport().cancel("p#1");
        runClientMain();   // 客户端晚了:函数照常执行,答复没人要

        CompletableFuture<String> next = ask("p#2", "gt.gt_seat.twice", "{\"n\":2}");
        awaitTrue(() -> !clientMain.isEmpty());
        runClientMain();
        assertTrue(next.get(10, TimeUnit.SECONDS).contains("\"value\":4"));
        assertFalse(withdrawn.isDone(), "the answer to a call the program stopped waiting for was delivered");
    }

    @Test
    void whenTheClientDisconnectsWhileAnswerIsAwaitedTheCallFailsAtItsLine() throws Exception {
        CompletableFuture<String> answer = ask("p#1", "gt.gt_seat.twice", "{\"n\":1}");
        awaitTrue(() -> !clientMain.isEmpty());
        pipe.disconnect();
        String reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("gt.gt_seat.twice runs on your owner's client")
                && reply.contains("its owner's client disconnected"), reply);
    }

    @Test
    void aSeatNobodySitsAtFailsTheCallAtOnce() throws Exception {
        pipe.disconnect();
        String reply = ask("p#1", "gt.gt_seat.twice", "{\"n\":1}").get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("has not opened her seat"), reply);
    }

    @Test
    void aCallTooBigForOneMessageFailsWithoutBeingSent() throws Exception {
        String text = "x".repeat(com.dwinovo.numen.api.network.Wire.MESSAGE_BYTES + 1);
        String reply = ask("p#1", "gt.gt_seat.length", "{\"text\":\"" + text + "\"}").get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("more than the") && reply.contains("so it was not sent"), reply);
        assertTrue(clientMain.isEmpty(), "a message that cannot be carried reached the client");
    }

    @Test
    void anAnswerTooBigForOneMessageIsReplacedByAFailureThatSaysSo() throws Exception {
        CompletableFuture<String> answer = ask("p#1", "gt.gt_seat.big", "{\"n\":" + (com.dwinovo.numen.api.network.Wire.MESSAGE_BYTES + 1) + "}");
        awaitTrue(() -> !clientMain.isEmpty());
        runClientMain();
        String reply = answer.get(10, TimeUnit.SECONDS);
        assertTrue(reply.contains("\"ok\":false") && reply.contains("Ask for less of it at a time"), reply);
    }

    @Test
    void aSilentClientLeavesTheCallWaitingForTheProgramsOwnTimeout() throws Exception {
        pipe.silence();
        CompletableFuture<String> answer = ask("p#1", "gt.gt_seat.twice", "{\"n\":1}");
        awaitTrue(() -> !clientMain.isEmpty());
        runClientMain();   // 函数执行了,答复发不回去
        assertFalse(answer.isDone());
        seats.transport().cancel("p#1");
    }
}
