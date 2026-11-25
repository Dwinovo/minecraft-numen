package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.brain.Bound;
import com.dwinovo.numen.api.brain.Mind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.script.HerModules;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.script.ScriptCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主人客户端上的大脑({@link LoopHost})经真的座位表({@link Seats})与进程内的管道({@link SeatPipe})坐在一只同伴的座位上:服务端那头是
 * 假的世界({@link StubSeatWorld}),大脑这头是产品里的那一份,模型是按事先排好的回复答的。线上的样子、认主人、续传、代数都真走一遍。
 */
class LoopHostTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** 按事先排好的回复答的"模型":记下每次收到的请求。 */
    private static final class Scripted implements Mind {
        final Deque<ModelOutcome> replies = new ArrayDeque<>();
        final List<ModelRequest> asked = new ArrayList<>();

        Scripted say(String text) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
            return this;
        }

        Scripted program(String id, String code) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn("", List.of(new LlmToolCall(id, new ScriptTool().name(),
                    "{\"code\":\"" + code + "\"}")), null), Usage.ZERO));
            return this;
        }

        @Override
        public Cause unavailable(Bound provider) {
            return null;
        }

        @Override
        public LlmEndpoint endpoint(Bound provider) {
            return LlmEndpoint.unresolved("");
        }

        @Override
        public void ask(Bound provider, UUID companion, ModelRequest request, CancelToken cancel,
                        Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone) {
            asked.add(request);
            onDone.accept(replies.poll());
        }
    }

    private static final ToolBook SCRIPT_ONLY = new ToolBook() {
        private final NumenTool script = new ScriptTool();

        @Override
        public List<NumenTool> all() {
            return List.of(script);
        }

        @Override
        public NumenTool resolve(String name) {
            return script.name().equals(name) ? script : null;
        }
    };

    @TempDir
    Path dir;

    private static final java.util.concurrent.Executor THREADS = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();

    /** 大脑的线程(客户端主线程):座位上来的回调都排在这里,用例在 {@link #until} 里一刻一刻地做。 */
    private final java.util.Queue<Runnable> clientMain = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private StubSeatWorld world;
    private Seats seats;
    private SeatPipe pipe;
    private Scripted model;
    private HerModules modules;
    private LoopHost host;

    @BeforeEach
    void seat() throws Exception {
        world = new StubSeatWorld().createdBy(HER, OWNER);
        seats = new Seats(world, THREADS);
        pipe = SeatPipe.connect(seats, OWNER, clientMain::add, THREADS);
        model = new Scripted();
        Io io = NumenIo.inline("test");
        Home home = new Home(dir.resolve("her"), io);
        Home library = new Home(dir.resolve("library"), io);
        modules = new HerModules(library, new HerModules.Builtins() {
            @Override
            public Set<String> names() {
                return Set.of();
            }

            @Override
            public boolean loaded() {
                return true;
            }

            @Override
            public String code(String name) {
                return null;
            }
        });
        LibraryHub hub = LibraryHub.local(library, Switches.inMemory(), List.of(), Set.of());
        host = new LoopHost(new LoopHost.Parts(HER, OWNER, home, clientMain::add, pipe.client(), model, SCRIPT_ONLY,
                () -> false, () -> EventQueue.DEFAULT_LEVEL, bound -> "a test persona", builtins -> modules,
                bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), id -> "主人"));
        host.bind(com.dwinovo.numen.api.brain.Slot.PERSONA,
                new Bound(OWNER, new com.dwinovo.numen.api.library.EntryId("my", "test")));
        seats.bodyEntered(HER);
        Opened opened = pipe.client().open(HER, 0).get(5, TimeUnit.SECONDS);
        host.attach(opened, Catalog.fromJson(pipe.client().catalog(HER).get(5, TimeUnit.SECONDS)));
    }

    /** 大脑一刻一刻地走,座位上来的回调在它的线程上做,直到条件成立;三秒还不成立就是没发生。 */
    private void until(String what, java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            for (Runnable task; (task = clientMain.poll()) != null; ) {
                task.run();
            }
            host.tick();
            Thread.sleep(5);
        }
        assertTrue(done.getAsBoolean(), what);
    }

    @Test
    void theOwnersWordsRunATurnThatCarriesHerStateReadAtTheStartOfTheRun() throws Exception {
        world.state = "<runtime_state><now/>she stands by the lake</runtime_state>";
        model.say("好的");

        host.submitPrompt("你好");
        until("the model was asked", () -> !model.asked.isEmpty());

        List<com.dwinovo.numen.api.agent.llm.ConvoState.Msg> messages = model.asked.getFirst().messages();
        assertTrue(messages.getLast().toString().contains("she stands by the lake"),
                "the request ends with the state the seat gave at the start of the run: " + messages.getLast());
        assertTrue(model.asked.getFirst().systemPrompt().contains("<persona>"), "the prompt is composed on the client");
    }

    @Test
    void aWorldEventArrivesInHerInboxCarriedAsOneEntryAndIsAcknowledged() throws Exception {
        EventQueue.Entry gone = new EventQueue.Entry(EventTypes.GONE, "<event kind=\"gone\"/>", 5L, false);

        seats.publish(HER, gone);
        until("it arrived", () -> !host.inbox().entries().isEmpty() && pipe.client().lastSeq(HER) == 1);

        assertEquals(List.of("<event kind=\"gone\"/>"),
                host.inbox().entries().stream().map(EventQueue.Entry::text).toList());
    }

    @Test
    void herDeathHaltsTheLoopBeforeTheEventIsHandedToHerAndRespawnThawsIt() throws Exception {
        seats.publish(HER, new EventQueue.Entry(EventTypes.DEATH, "You just died", 5L, true));
        until("she is held", () -> host.status().hold() == Hold.DEAD);
        assertTrue(model.asked.isEmpty(), "the urgent death note did not wake her into a request that would be thrown away");

        model.say("我回来了");
        seats.publish(HER, new EventQueue.Entry(EventTypes.RESPAWNED, "You respawned", 6L, true));
        until("she answered after the respawn", () -> model.asked.size() == 1);
        assertTrue(host.status().hold() != Hold.DEAD, "respawn thawed her: " + host.status());
    }

    @Test
    void aProgramTheModelWritesRunsAtTheBodyWithHerModulesAndItsReceiptComesBack() throws Exception {
        modules.save("my.pit", "-- Dig a pit.\nlocal M = {}\nfunction M.dig() end\nreturn M\n");
        model.program("call-1", "my.pit.dig()").say("挖好了");

        host.submitPrompt("挖个坑");
        until("the program reached the body", () -> world.programs.running.containsKey("call-1"));

        assertEquals(Set.of("my.pit"), world.programs.modulesOf("call-1").hers().keySet(),
                "the module she wrote reached the body through modules/get");
        world.programs.finish("call-1", new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null, 0, java.util.Map.of()));
        until("the receipt woke the second request", () -> model.asked.size() == 2);
    }

    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void aFriendsWhisperIsAnsweredAsAWhisperToHimAndTheProgramsThatFollowAreHis() throws Exception {
        world.sharedWith.put(HER, Set.of(FRIEND));
        model.program("call-2", "return 1").say("在的");
        com.dwinovo.numen.api.brain.Spoken whisper = new com.dwinovo.numen.api.brain.Spoken(FRIEND,
                com.dwinovo.numen.api.brain.Venue.WHISPER, null);

        seats.publish(HER, new EventQueue.Entry(EventTypes.QUERY,
                EventQueue.query("你好", 6000, "player@abcdef (Friend)", "whisper", null), 5L, false), whisper);

        until("her program reached the body", () -> world.programs.running.containsKey("call-2"));
        assertEquals(FRIEND, world.programs.keepers.get("call-2"), "the program acts for the one she is answering");
        world.programs.finish("call-2", new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null, 0, java.util.Map.of()));
        until("she answered in his venue", () -> !world.spokenTo.isEmpty());
        assertEquals(List.of(whisper), world.spokenTo, "her words go back as the whisper they answer");
        assertEquals(List.of("在的"), world.said);
    }

    @Test
    void theOwnersOwnWordsInThePanelAreNotSaidAloudAndHisProgramsAreHis() throws Exception {
        model.program("call-3", "return 1").say("好的");

        host.submitPrompt("你好");

        until("her program reached the body", () -> world.programs.running.containsKey("call-3"));
        assertEquals(OWNER, world.programs.keepers.get("call-3"));
        world.programs.finish("call-3", new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), List.of(), null, 0, java.util.Map.of()));
        until("she answered", () -> model.asked.size() == 2);
        assertTrue(world.said.isEmpty(), "the panel shows her reply itself; the server says nothing aloud: " + world.said);
    }

    @Test
    void aRunsStartAndEndAreReportedToTheServerOnce() throws Exception {
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        var started = com.dwinovo.numen.api.entity.CompanionEvents.subscribe(com.dwinovo.numen.api.CompanionEvent.RUN_STARTED,
                e -> heard.add("started"));
        var ended = com.dwinovo.numen.api.entity.CompanionEvents.subscribe(com.dwinovo.numen.api.CompanionEvent.RUN_ENDED,
                e -> heard.add("ended " + e.outcome()));
        try {
            model.say("好的");

            host.submitPrompt("你好");

            until("the run was reported", () -> heard.size() >= 2);
            assertEquals(List.of("started", "ended DONE"), heard);
        } finally {
            started.close();
            ended.close();
        }
    }
}
