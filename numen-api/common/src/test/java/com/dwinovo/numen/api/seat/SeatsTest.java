package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.script.ScriptCall;
import com.dwinovo.numen.mcp.rpc.RpcException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 座位:谁坐得上、方法怎么映射到世界、事件的序号与确认与续传、代数、断线切断程序并在重连时补上结局。
 * 客户端与服务端经进程内的管道({@link SeatPipe})说话,消息照线上的样子过一遍。
 */
@Tag("mc")
class SeatsTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private static final Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private StubSeatWorld world;
    private Seats seats;
    private SeatPipe pipe;
    private SeatClient client;
    private List<SeatEvent> events;

    @BeforeEach
    void wire() {
        world = new StubSeatWorld().createdBy(HER, OWNER);
        seats = new Seats(world, THREADS);
        pipe = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        client = pipe.client();
        events = new CopyOnWriteArrayList<>();
        client.onEvent(events::add);
    }

    @AfterEach
    void unplug() {
        seats.clear();
    }

    private static <T> T get(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    /** 请求以 JSON-RPC 错误收场:错误码。 */
    private static int refusedWith(CompletableFuture<?> future) throws Exception {
        ExecutionException failed = assertThrows(ExecutionException.class, () -> get(future));
        return assertInstanceOf(RpcException.class, failed.getCause()).error().code();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("it did not happen in ten seconds");
            }
            Thread.sleep(2);
        }
    }

    private static EventQueue.Entry entry(String text) {
        return new EventQueue.Entry("hungry", text, 5L, true);
    }

    private static Program.Outcome outcome(String message) {
        return new Program.Outcome("{\"success\":true,\"message\":\"" + message + "\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null),
                List.of(new ScriptCall.Called("plugin.status.self", "plugin.status.self()", null, null)), null);
    }

    // ---- 谁坐得上 ----

    @Test
    void openAnswersHerGenerationTheCatalogVersionAndTheSeqsTheServerKeeps() throws Exception {
        seats.bodyEntered(HER);
        Opened opened = get(client.open(HER, 0));
        assertEquals(new Opened(1, "v1", 0, 1, false), opened);
    }

    @Test
    void onlyTheCreatorsConnectionMaySitAndOthersAreRefusedAsIfSheWereNotThere() throws Exception {
        SeatPipe other = SeatPipe.connect(seats, STRANGER, Runnable::run, THREADS);
        assertEquals(SeatErrors.NOT_YOURS, refusedWith(other.client().open(HER, 0)));
        assertEquals(SeatErrors.NOT_YOURS, refusedWith(client.open(UUID.randomUUID(), 0)),
                "a companion that does not exist is refused the same way");
    }

    @Test
    void aMethodOnASeatThatIsNotOpenOnThisConnectionIsRefused() throws Exception {
        assertEquals(SeatErrors.NO_SEAT, refusedWith(client.state(HER)));
        assertEquals(SeatErrors.NO_SEAT, refusedWith(client.run(HER, 0, "p1", "return 1", OWNER)));
        assertTrue(world.programs.runs.isEmpty());
    }

    // ---- 方法 ----

    @Test
    void stateIsHerStateWithTheGameTimeAndGeneration() throws Exception {
        get(client.open(HER, 0));
        seats.bodyEntered(HER);
        assertEquals(new SeatClient.Sitting(1, new SeatState(world.state, null, 100, 6000)), get(client.state(HER)));
    }

    @Test
    void interruptCutStopBodyAndSayReachTheWorld() throws Exception {
        get(client.open(HER, 0));
        get(client.interrupt(HER, "p1", "your owner spoke"));
        get(client.cut(HER, "p1", true));
        get(client.stopBody(HER));
        get(client.say(HER, "hello there", com.dwinovo.numen.api.talk.Spoken.panel(OWNER)));
        assertEquals(List.of("p1: your owner spoke"), world.programs.interrupts);
        assertEquals(List.of("p1: true"), world.programs.cuts);
        assertEquals(List.of(HER), world.programs.bodyStops);
        assertEquals(List.of("hello there"), world.said);
    }

    @Test
    void aProgramRunAnswersItsStructuredOutcomeWithHerStateAndTheGameTime() throws Exception {
        seats.bodyEntered(HER);
        get(client.open(HER, 0));
        CompletableFuture<ProgramResult> run = client.run(HER, 1, "p1", "return 1", OWNER);
        await(() -> world.programs.running.containsKey("p1"));
        assertEquals(List.of("p1: return 1"), world.programs.runs);
        world.gameTime = 250;
        world.programs.finish("p1", outcome("all done"));

        ProgramResult result = get(run);
        assertEquals("p1", result.program());
        assertEquals(1, result.generation());
        assertEquals(new SeatState(world.state, null, 250, 6000), result.state());
        assertEquals(outcome("all done"), result.outcome());
        assertEquals("all done", result.outcome().message());
    }

    @Test
    void twoProgramsWithTheSameNameCannotRunAtOnceOnOneSeat() throws Exception {
        get(client.open(HER, 0));
        CompletableFuture<ProgramResult> first = client.run(HER, 0, "p1", "return 1", OWNER);
        await(() -> world.programs.running.containsKey("p1"));
        assertEquals(com.dwinovo.numen.mcp.rpc.RpcError.INVALID_PARAMS, refusedWith(client.run(HER, 0, "p1", "return 2", OWNER)));
        world.programs.finish("p1", outcome("one"));
        assertEquals("one", get(first).outcome().message());
    }

    // ---- 代数 ----

    @Test
    void aProgramFromABrainThatBelievesAnOlderBodyIsRefusedAndNeverRun() throws Exception {
        seats.bodyEntered(HER);
        get(client.open(HER, 0));
        seats.bodyEntered(HER);   // 她重生了:代数 2
        assertEquals(SeatErrors.STALE_GENERATION, refusedWith(client.run(HER, 1, "p1", "return 1", OWNER)));
        assertTrue(world.programs.runs.isEmpty(), "a program for a body that is gone was started");

        CompletableFuture<ProgramResult> current = client.run(HER, 2, "p2", "return 1", OWNER);
        await(() -> world.programs.running.containsKey("p2"));
        world.programs.finish("p2", outcome("fine"));
        assertEquals(2, get(current).generation());
    }

    @Test
    void theErrorSaysWhichGenerationSheIsAtAndWhichYouBelieved() throws Exception {
        seats.bodyEntered(HER);
        get(client.open(HER, 0));
        ExecutionException failed = assertThrows(ExecutionException.class, () -> get(client.run(HER, 7, "p1", "x", OWNER)));
        String message = ((RpcException) failed.getCause()).error().message();
        assertTrue(message.contains("generation 1") && message.contains("not 7"), message);
    }

    // ---- 事件:序号、代数、确认、续传 ----

    @Test
    void eventsGetIncreasingSeqsAndCarryTheGenerationAndTheTimes() throws Exception {
        seats.bodyEntered(HER);
        get(client.open(HER, 0));
        seats.publish(HER, entry("a"));
        seats.bodyEntered(HER);
        world.gameTime = 400;
        seats.publish(HER, entry("b"));

        assertEquals(2, events.size());
        SeatEvent first = events.get(0);
        SeatEvent second = events.get(1);
        assertEquals(List.of(1L, 2L), List.of(first.seq(), second.seq()));
        assertEquals(List.of(1L, 2L), List.of(first.generation(), second.generation()));
        assertEquals("a", first.text());
        assertEquals(100, first.gameTime());
        assertEquals(6000, first.dayTime());
        assertEquals(400, second.gameTime());
        assertEquals(2, client.lastSeq(HER));
    }

    @Test
    void anEventForASeatNobodyOpenedHasNowhereToGo() throws Exception {
        seats.publish(HER, entry("a"));
        assertTrue(events.isEmpty());
        assertEquals(0, get(client.open(HER, 0)).lastSeq());
    }

    @Test
    void whatTheClientDidNotAcknowledgeIsReplayedAfterReconnectingAndWhatItDidIsNot() throws Exception {
        get(client.open(HER, 0));
        for (String text : List.of("a", "b", "c")) {
            seats.publish(HER, entry(text));
        }
        get(client.ack(HER, 2));
        pipe.disconnect();
        seats.publish(HER, entry("d"));   // 没有人坐着时也留着

        // 重连:没有报序号,服务端还留着的是 c、d
        List<SeatEvent> again = new CopyOnWriteArrayList<>();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        second.client().onEvent(again::add);
        Opened opened = get(second.client().open(HER, 0));
        assertEquals(List.of("c", "d"), again.stream().map(SeatEvent::text).toList());
        assertEquals(List.of(3L, 4L), again.stream().map(SeatEvent::seq).toList());
        assertEquals(new Opened(0, "v1", 4, 3, false), opened);

        // 再重连一次,这次报了收到 3 号:只补 4
        second.disconnect();
        List<SeatEvent> third = new CopyOnWriteArrayList<>();
        SeatPipe next = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        next.client().onEvent(third::add);
        get(next.client().open(HER, 3));
        assertEquals(List.of("d"), third.stream().map(SeatEvent::text).toList());
    }

    @Test
    void aSeqAheadOfTheServersMeansTheServerRestartedAndEverythingItKeepsIsReplayed() throws Exception {
        get(client.open(HER, 0));
        seats.publish(HER, entry("a"));
        pipe.disconnect();

        List<SeatEvent> again = new CopyOnWriteArrayList<>();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        second.client().onEvent(again::add);
        Opened opened = get(second.client().open(HER, 500));
        assertTrue(opened.reset());
        assertEquals(List.of("a"), again.stream().map(SeatEvent::text).toList());
        assertEquals(1, second.client().lastSeq(HER), "the client's mark follows the server's after a reset");
    }

    @Test
    void whenTheOutboxOverflowsTheOpenAnswerSaysWhereTheKeptEventsStart() throws Exception {
        get(client.open(HER, 0));
        for (int i = 0; i < Seat.OUTBOX_CAP + 5; i++) {
            seats.publish(HER, entry("e" + i));
        }
        pipe.disconnect();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        Opened opened = get(second.client().open(HER, 0));
        assertEquals(Seat.OUTBOX_CAP + 5, opened.lastSeq());
        assertEquals(6, opened.firstSeq(), "the five oldest were dropped, and the answer says so");
    }

    @Test
    void anAcknowledgementOfSomethingNeverSentIsRefused() throws Exception {
        get(client.open(HER, 0));
        assertEquals(com.dwinovo.numen.mcp.rpc.RpcError.INVALID_PARAMS, refusedWith(client.ack(HER, 9)));
    }

    // ---- 断线 ----

    @Test
    void disconnectingCutsAProgramStartedThroughTheSeatAndItsOutcomeIsDeliveredOnResume() throws Exception {
        seats.bodyEntered(HER);
        get(client.open(HER, 0));
        world.programs.onCut = outcome("this turn was cut off");
        CompletableFuture<ProgramResult> run = client.run(HER, 1, "p1", "plugin.move.walk()", OWNER);
        await(() -> world.programs.running.containsKey("p1"));

        pipe.disconnect();

        assertEquals(List.of("p1: false"), world.programs.cuts, "the program was cut, and her body left to the usual rules");
        assertThrows(ExecutionException.class, () -> get(run));   // 答复没有去处
        List<SeatEvent> resumed = new CopyOnWriteArrayList<>();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        second.client().onEvent(resumed::add);
        get(second.client().open(HER, 0));
        assertEquals(1, resumed.size());
        SeatEvent kept = resumed.get(0);
        assertEquals("program_stopped", kept.type());
        assertTrue(kept.text().contains("this turn was cut off"), kept.text());
        assertEquals(outcome("this turn was cut off"), kept.outcome());
        assertEquals(1, kept.generation());
    }

    @Test
    void aProgramThatEndsOnItsOwnAfterTheConnectionDroppedIsKeptTooNotLost() throws Exception {
        get(client.open(HER, 0));
        CompletableFuture<ProgramResult> run = client.run(HER, 0, "p1", "x", OWNER);
        await(() -> world.programs.running.containsKey("p1"));
        pipe.disconnect();   // 程序没被切断(这个世界的主机不理切断),照样跑完
        assertThrows(ExecutionException.class, () -> get(run));
        world.programs.finish("p1", outcome("ended anyway"));

        List<SeatEvent> resumed = new CopyOnWriteArrayList<>();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        second.client().onEvent(resumed::add);
        get(second.client().open(HER, 0));
        assertNotNull(resumed.get(0).outcome());
        assertEquals("ended anyway", resumed.get(0).outcome().message());
    }

    @Test
    void aProgramThatEndedBeforeTheDisconnectIsNotDeliveredTwice() throws Exception {
        get(client.open(HER, 0));
        CompletableFuture<ProgramResult> run = client.run(HER, 0, "p1", "x", OWNER);
        await(() -> world.programs.running.containsKey("p1"));
        world.programs.finish("p1", outcome("done"));
        get(run);
        pipe.disconnect();
        assertTrue(world.programs.cuts.isEmpty(), "nothing was running to cut");

        List<SeatEvent> resumed = new CopyOnWriteArrayList<>();
        SeatPipe second = SeatPipe.connect(seats, OWNER, Runnable::run, THREADS);
        second.client().onEvent(resumed::add);
        get(second.client().open(HER, 0));
        assertFalse(resumed.stream().anyMatch(e -> e.outcome() != null));
    }

    // ---- 目录 ----

    @Test
    void theCatalogIsFetchedByVersionAndAChangeIsAnnouncedOnceWhenTheVersionMoves() throws Exception {
        get(client.open(HER, 0));
        assertEquals("v1", get(client.catalog(HER)).get("version").getAsString());
        List<String> announced = new CopyOnWriteArrayList<>();
        client.onCatalogChanged((companion, version) -> announced.add(companion + "=" + version));

        Seat seat = seats.seated(HER);
        seat.catalogMayHaveChanged();
        assertTrue(announced.isEmpty(), "nothing changed, nothing announced");

        world.catalogVersion.set("v2");
        seat.catalogMayHaveChanged();
        seat.catalogMayHaveChanged();
        assertEquals(List.of(HER + "=v2"), announced);
        assertEquals("v2", get(client.catalog(HER)).get("version").getAsString());
    }

    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void aProgramRunsForTheKeeperItNamesAndAnyoneNotOnHerTableIsRefused() throws Exception {
        get(client.open(HER, 0));
        seats.bodyEntered(HER);
        world.sharedWith.put(HER, java.util.Set.of(FRIEND));

        CompletableFuture<ProgramResult> his = client.run(HER, 1, "p1", "return 1", FRIEND);
        await(() -> world.programs.keepers.containsKey("p1"));
        assertEquals(FRIEND, world.programs.keepers.get("p1"), "the runner is told who the program acts for");
        world.programs.finish("p1", Program.Outcome.refused("done"));
        get(his);

        UUID stranger = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
        assertEquals(com.dwinovo.numen.mcp.rpc.RpcError.INVALID_PARAMS, refusedWith(client.run(HER, 1, "p2", "return 1",
                stranger)));
        assertTrue(world.programs.keepers.get("p2") == null, "a stranger's program never reached the body");
    }

    @Test
    void herWordsGoToTheSpeakerShePointsAtOnlyIfHeIsOneOfHerKeepers() throws Exception {
        get(client.open(HER, 0));
        world.sharedWith.put(HER, java.util.Set.of(FRIEND));
        com.dwinovo.numen.api.talk.Spoken whisper = new com.dwinovo.numen.api.talk.Spoken(FRIEND,
                com.dwinovo.numen.api.talk.Venue.WHISPER, null);

        get(client.say(HER, "你好", whisper));

        assertEquals(List.of(whisper), world.spokenTo);
        assertEquals(com.dwinovo.numen.mcp.rpc.RpcError.INVALID_PARAMS, refusedWith(client.say(HER, "x",
                new com.dwinovo.numen.api.talk.Spoken(UUID.randomUUID(), com.dwinovo.numen.api.talk.Venue.WHISPER, null))));
    }

    @Test
    void theRunsTheBrainReportsAreFiredToPluginsFromThisOnePlaceInOrder() throws Exception {
        get(client.open(HER, 0));
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        var started = com.dwinovo.numen.api.entity.CompanionEvents.subscribe(com.dwinovo.numen.api.CompanionEvent.RUN_STARTED,
                e -> heard.add("started " + e.companion() + " " + e.runId()));
        var ended = com.dwinovo.numen.api.entity.CompanionEvents.subscribe(com.dwinovo.numen.api.CompanionEvent.RUN_ENDED,
                e -> heard.add("ended " + e.runId() + " " + e.outcome() + " " + e.reason()));
        try {
            client.runStarted(HER, 3);
            client.runEnded(HER, 3, "HALTED", "halt.owner_stop");
            client.runEnded(UUID.randomUUID(), 4, "DONE", "");   // 没坐着座位的同伴:不理

            assertEquals(List.of("started " + HER + " 3", "ended 3 HALTED halt.owner_stop"), heard);
        } finally {
            started.close();
            ended.close();
        }
    }
}
