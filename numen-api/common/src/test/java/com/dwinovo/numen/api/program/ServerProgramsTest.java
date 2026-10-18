package com.dwinovo.numen.api.program;

import com.dwinovo.numen.script.ApiReply;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.SdkFixture;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.google.gson.JsonElement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 整段程序在服务端跑:服务端函数与客户端函数在一段程序里交替调都对,她的模块(程序中途存了新的、删了)、
 * 并发上限、停止与打断。客户端函数走回环传输(请求与答复各过一遍线上的编码),没有真客户端的地方用同一条路。
 */
class ServerProgramsTest {

    /** 一组两端的函数:服务端的 up 加一,客户端的 twice 乘二。 */
    public static final class Mix {

        private Mix() {}

        public record N(@Doc("A number.") int n) {}

        @Fn("Add one, on the server.")
        @Example("gt.gt_mix.up(1)")
        public static int up(ServerCall call, N args) {
            return args.n() + 1;
        }

        @Fn("Double, on the owner's client.")
        @Example("gt.gt_mix.twice(2)")
        public static int twice(ClientCall call, N args) {
            return args.n() * 2;
        }
    }

    @BeforeAll
    static void register() throws java.io.IOException {
        SdkFixture.register("gt_mix", Mix.class);
        // 这个类自己的模块目录:别的测试类可能已经把目录换成别的了
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("numen-programs-lua-");
        dir.toFile().deleteOnExit();
        var hub = com.dwinovo.numen.api.library.LibraryHub.headless(dir);
        java.util.UUID owner = java.util.UUID.randomUUID();
        Modules.init(uuid -> hub.library(Modules.KIND, owner));
    }

    private static final String PIT = "-- A pit.\nlocal M = {}\n---Seven.\nfunction M.seven() return 7 end\nreturn M\n";

    /** 推进车道直到条件成立;十秒还不成立是程序卡住了。 */
    private static void pumpUntil(BooleanSupplier done) {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the program did not get there in ten seconds");
            }
            ServerPrograms.pump();
            Thread.onSpinWait();
        }
    }

    /** 反向请求停在这里,测试决定什么时候答。 */
    private static final class Held implements ClientTransport {
        record Request(String callId, String function, Consumer<String> done) {}

        final List<Request> held = new CopyOnWriteArrayList<>();
        /** 传输被告知不再要答复的调用编号。 */
        final List<String> cancelled = new CopyOnWriteArrayList<>();

        @Override
        public void request(com.dwinovo.numen.api.entity.NumenPlayer her,
                            com.dwinovo.numen.api.network.payload.ClientCallPayload request, Consumer<String> done) {
            held.add(new Request(request.callId(), request.function(), done));
        }

        @Override
        public void cancel(String callId) {
            cancelled.add(callId);
        }

        void answer(int index, Object value) {
            held.get(index).done().accept(ApiReply.value(new com.google.gson.Gson().toJsonTree(value)).toString());
        }
    }

    /** 跑一段程序,回执在 {@link #result}。 */
    private static final class Started {
        final String id = "p-" + UUID.randomUUID();
        final AtomicReference<Program.Outcome> result = new AtomicReference<>();

        Program.Outcome outcome() {
            return result.get();
        }

        /** 程序 {@code return} 的值:同进程,从结局对象读,不经线。 */
        JsonElement returned() {
            return com.dwinovo.numen.script.JsonValues.toJson(outcome().returned());
        }

        String message() {
            return outcome().message();
        }
    }

    private static Started start(UUID companion, UUID owner, String code, ClientTransport transport) {
        Started started = new Started();
        ServerPrograms.run(null, companion, owner,
                new ServerPrograms.Request(started.id, code, Modules.factory(), true), transport, CallObserver.NONE,
                started.result::set);
        return started;
    }

    // ---- 两端的函数交替 ----

    @Test
    void serverAndClientFunctionsCalledInTurnInOneProgramAreAllAnswered() {
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), """
                local a = gt.gt_mix.up(1)
                local b = gt.gt_mix.twice(a)
                local c = gt.gt_mix.up(b)
                return c
                """);
        assertTrue(run.ok(), run.message());
        assertEquals(5, run.returned().getAsInt(), "up(1)=2, twice(2)=4, up(4)=5");
        assertEquals(3, run.replies().size());
        assertTrue(run.message().contains("3 calls"), run.message());
    }

    // ---- 她的模块 ----

    /** 跑一段程序,她的模块是 {@link #register} 给的目录里的文件。 */
    private static ApiTester.Run files(UUID companion, String code) {
        return ApiTester.run(null, companion, code, Modules.of(companion));
    }

    @Test
    void aModuleTheProgramSavesIsUsedByTheSameProgramAndKeptForTheNext() {
        UUID companion = UUID.randomUUID();
        // 程序开跑时就有的名字空间里,中途存的模块同一段程序就能用
        ApiTester.Run seed = files(companion, "numen.module.save([[" + PIT + "]], {name = \"my.pit_seed\"})");
        assertTrue(seed.ok(), seed.message());
        ApiTester.Run run = files(companion, "numen.module.save([[" + PIT + "]], {name = \"my.pit_mid\"})\n"
                + "return my.pit_mid.seven()");
        assertTrue(run.ok(), run.message());
        assertEquals(7, run.returned().getAsInt(), "saved in the middle of the program, used right after");

        ApiTester.Run next = files(companion, "return my.pit_mid.seven()");
        assertTrue(next.ok(), next.message());
        assertEquals(7, next.returned().getAsInt(), "the file is the one source: the next program sees it");
    }

    @Test
    void aModuleTheProgramDeletesIsGoneFromTheNextProgram() {
        UUID companion = UUID.randomUUID();
        files(companion, "numen.module.save([[" + PIT + "]], {name = \"my.pit_gone\"})");
        ApiTester.Run deleting = files(companion, """
                local before = my.pit_gone.seven()
                numen.module.delete("my.pit_gone")
                return before
                """);
        assertTrue(deleting.ok(), deleting.message());
        ApiTester.Run next = files(companion, """
                local ok = pcall(function() return my.pit_gone.seven() end)
                return tostring(ok)
                """);
        assertEquals("false", next.returned().getAsString(), "the file is gone, so the next program lacks the module");
    }

    // ---- 回执交出去时,这段程序已经不占位 ----

    /**
     * 回调里马上起下一段(模型拿到回执就接着派下一个程序)不能被"上一段还没摘掉"拒绝。一段接一段连着起,
     * 主线程一直在推车道,回执一排上就被取走执行;登记若在回执之后才摘,总有几段撞上。
     */
    @Test
    void aProgramStartedFromTheCallbackOfTheOneThatJustEndedIsNeverRefusedForItsPredecessor() {
        UUID companion = UUID.randomUUID();
        int rounds = 4000;
        List<String> refusals = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicInteger finished = new java.util.concurrent.atomic.AtomicInteger();
        AtomicReference<Runnable> chain = new AtomicReference<>();
        chain.set(() -> ServerPrograms.run(null, companion, companion,
                new ServerPrograms.Request("chain-" + UUID.randomUUID(), "return 1", Modules.factory(), true),
                new LoopbackTransport(), CallObserver.NONE, outcome -> {
                    if (outcome.receipt().contains("already running")) {
                        refusals.add(outcome.receipt());
                    }
                    if (finished.incrementAndGet() < rounds) {
                        chain.get().run();
                    }
                }));
        chain.get().run();

        pumpUntil(() -> finished.get() >= rounds);

        assertTrue(refusals.isEmpty(), refusals.size() + " of " + rounds + " programs were refused for the one before");
    }

    // ---- 并发上限 ----

    /** 停在一个客户端函数上的程序:在 {@code held} 里等答复。 */
    private static Started waiting(UUID companion, UUID owner, Held held) {
        Started started = start(companion, owner, "return gt.gt_mix.twice(1)", held);
        int before = held.held.size();
        pumpUntil(() -> held.held.size() > before || started.result.get() != null);
        return started;
    }

    @Test
    void oneProgramAtATimeOnACompanionAndAMostPerOwner() {
        UUID owner = UUID.randomUUID();
        Held held = new Held();
        UUID first = UUID.randomUUID();
        List<UUID> all = new ArrayList<>(List.of(first));
        waiting(first, owner, held);

        Started again = start(first, owner, "return 1", held);
        assertTrue(again.message().contains("another program is already running"), again.message());

        for (int i = 1; i < ProgramLimits.PER_OWNER; i++) {
            UUID more = UUID.randomUUID();
            all.add(more);
            waiting(more, owner, held);
        }
        Started over = start(UUID.randomUUID(), owner, "return 1", held);
        assertEquals(com.dwinovo.numen.script.ScriptCall.Status.ERROR, over.outcome().ending().status());
        assertTrue(over.message().contains("the most one owner may have at a time"), over.message());

        ServerPrograms.ownerLeft(owner);
        pumpUntil(() -> all.stream().noneMatch(ServerPrograms::running));
    }

    @Test
    void aMostForTheWholeServer() {
        List<UUID> owners = new ArrayList<>();
        List<UUID> companions = new ArrayList<>();
        Held held = new Held();
        while (companions.size() < ProgramLimits.SERVER_WIDE) {
            UUID owner = UUID.randomUUID();
            owners.add(owner);
            for (int i = 0; i < ProgramLimits.PER_OWNER && companions.size() < ProgramLimits.SERVER_WIDE; i++) {
                UUID companion = UUID.randomUUID();
                companions.add(companion);
                waiting(companion, owner, held);
            }
        }
        UUID late = UUID.randomUUID();
        Started over = start(UUID.randomUUID(), late, "return 1", held);
        assertTrue(over.message().contains("as many programs as it allows"), over.message());

        owners.forEach(ServerPrograms::ownerLeft);
        pumpUntil(() -> companions.stream().noneMatch(ServerPrograms::running));
    }

    // ---- 停止与打断 ----

    @Test
    void anInterruptStopsTheProgramWhenTheCallInFlightIsAnswered() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, "gt.gt_mix.twice(1)\ngt.gt_mix.up(2)", held);
        pumpUntil(() -> !held.held.isEmpty());

        ServerPrograms.interrupt(companion, "some-other-program", "your owner spoke");
        ServerPrograms.interrupt(companion, program.id, "your owner spoke");
        assertNull(program.result.get(), "the call in flight is not interrupted");
        held.answer(0, 2);
        pumpUntil(() -> program.result.get() != null);

        assertTrue(program.message().startsWith("The script stopped at line 1 (gt.gt_mix.twice) after 1 call: "
                + "your owner spoke"), program.message());
        assertEquals(1, held.held.size(), "the second call was not made");
    }

    @Test
    void anInterruptWhileTheProgramWaitsForAJobStopsItAndTheJobKeepsRunning() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, "gt.gt_mix.twice(1)\ngt.gt_mix.up(2)", held);
        pumpUntil(() -> !held.held.isEmpty());
        held.held.get(0).done().accept(ApiReply.job("t9").toString());
        // 受理的回执处理完,程序停在等 t9 收尾上
        long until = System.nanoTime() + 300_000_000L;
        while (System.nanoTime() < until) {
            ServerPrograms.pump();
            Thread.onSpinWait();
        }
        assertNull(program.result.get());

        ServerPrograms.interrupt(companion, program.id, "your owner spoke");
        pumpUntil(() -> program.result.get() != null);
        assertTrue(program.message().contains("your owner spoke; t9 keeps running"), program.message());
    }

    @Test
    void cuttingTheTurnOffStopsTheProgramAtOnceAndALateAnswerIsDropped() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, "gt.gt_mix.twice(1)\ngt.gt_mix.up(2)", held);
        pumpUntil(() -> !held.held.isEmpty());

        ServerPrograms.cutOff(companion, program.id, false);
        pumpUntil(() -> program.result.get() != null);
        assertTrue(program.message().contains("this turn was cut off"), program.message());

        held.answer(0, 2);
        ServerPrograms.pump();
        assertEquals(1, held.held.size());
    }

    // ---- 反向请求的时限与撤销 ----

    /** 服务器刻前进 {@code n} 刻(程序排着的调用在每刻里照常执行)。 */
    private static void tick(int n) {
        for (int i = 0; i < n; i++) {
            ServerPrograms.tick();
        }
    }

    private static final String TWICE_THEN_UP = """
            local ok, err = pcall(gt.gt_mix.twice, 1)
            local after = gt.gt_mix.up(1)
            return (ok and "answered" or err.kind) .. "|" .. after
            """;

    @Test
    void aClientFunctionTheClientNeverAnswersFailsWhenItsTimeIsUpAndTheProgramGoesOn() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, TWICE_THEN_UP, held);
        pumpUntil(() -> !held.held.isEmpty());

        tick(ProgramLimits.CLIENT_ANSWER_TICKS - 1);
        assertNull(program.result.get(), "still within its time");
        tick(1);
        pumpUntil(() -> program.result.get() != null);

        assertEquals("timeout|2", program.returned().getAsString(),
                "the call failed as a timeout, and the next line ran");
        assertEquals(List.of(held.held.get(0).callId()), held.cancelled, "the transport was told to drop it");
        held.answer(0, 2);
        ServerPrograms.pump();
        assertEquals("timeout|2", program.returned().getAsString(),
                "a late answer is ignored");
    }

    @Test
    void theTimeoutSaysWhoDidNotAnswerWithinHowLongAndHintsTheSameCallAgain() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, """
                local ok, err = pcall(gt.gt_mix.twice, 7)
                return err.message .. "|" .. err.hint
                """, held);
        pumpUntil(() -> !held.held.isEmpty());
        tick(ProgramLimits.CLIENT_ANSWER_TICKS);
        pumpUntil(() -> program.result.get() != null);

        assertEquals("your owner's client did not answer gt.gt_mix.twice within "
                        + ProgramLimits.CLIENT_ANSWER_TICKS / 20 + " seconds. A client function only reads, so the same "
                        + "call is safe to run again.|gt.gt_mix.twice(7)",
                program.returned().getAsString());
    }

    @Test
    void aClientAnswerBeforeTheTimeIsUpIsTheCallsResult() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, TWICE_THEN_UP, held);
        pumpUntil(() -> !held.held.isEmpty());

        tick(ProgramLimits.CLIENT_ANSWER_TICKS - 1);
        held.answer(0, 2);
        pumpUntil(() -> program.result.get() != null);
        tick(ProgramLimits.CLIENT_ANSWER_TICKS);

        assertEquals("answered|2", program.returned().getAsString());
        assertTrue(held.cancelled.isEmpty(), "an answered call has nothing left to drop: " + held.cancelled);
    }

    @Test
    void whenAProgramEndsHoweverTheRequestsItWasWaitingOnAreDroppedAndLateAnswersAreIgnored() {
        Held held = new Held();
        UUID owner = UUID.randomUUID();
        UUID cutOff = UUID.randomUUID();
        UUID leaver = UUID.randomUUID();
        Started a = start(cutOff, owner, "return gt.gt_mix.twice(1)", held);
        Started b = start(leaver, owner, "return gt.gt_mix.twice(2)", held);
        pumpUntil(() -> held.held.size() == 2);

        ServerPrograms.cutOff(cutOff, a.id, false);
        pumpUntil(() -> a.result.get() != null);
        assertEquals(List.of(a.id + "#1"), held.cancelled, "the cut-off program's request was dropped");

        ServerPrograms.ownerLeft(owner);
        pumpUntil(() -> b.result.get() != null);
        assertEquals(2, held.cancelled.size(), "the program that ended with its owner dropped its request too");

        held.answer(0, 2);
        held.answer(1, 4);
        ServerPrograms.pump();
        assertEquals(2, held.cancelled.size(), "late answers change nothing");
    }

    @Test
    void aProgramThatEndsBeforeItsRequestWasSentNeverSendsIt() {
        UUID companion = UUID.randomUUID();
        Held held = new Held();
        Started program = start(companion, companion, "return gt.gt_mix.twice(1)", held);
        // 请求排在车道里、没有推进,程序就被切断了:等它收尾(登记摘掉)之后才推进车道
        ServerPrograms.cutOff(companion, program.id, false);
        while (ServerPrograms.running(companion)) {
            Thread.onSpinWait();
        }
        pumpUntil(() -> program.result.get() != null);
        assertTrue(held.held.isEmpty(), "the request of an ended program went out: " + held.held);
    }

    @Test
    void aClientFunctionWhenTheOwnersClientCannotBeReachedFailsAtItsLine() {
        UUID companion = UUID.randomUUID();
        Started program = start(companion, companion, """
                local ok, err = pcall(gt.gt_mix.twice, 1)
                return err.kind .. "|" .. err.message
                """, NetworkTransport.INSTANCE);
        pumpUntil(() -> program.result.get() != null);
        assertEquals("failed|gt.gt_mix.twice runs on your owner's client, and its owner is not connected",
                program.returned().getAsString());
    }
}
