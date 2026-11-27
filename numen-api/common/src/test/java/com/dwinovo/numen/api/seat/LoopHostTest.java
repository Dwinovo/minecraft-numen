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
import com.dwinovo.numen.api.agent.tool.AskUserQuestionTool;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.Mind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mind.LoopHost;
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

        Scripted ask(String id, String args) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn("", List.of(new LlmToolCall(id,
                    AskUserQuestionTool.NAME, args)), null), Usage.ZERO));
            return this;
        }

        Scripted program(String id, String code) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn("", List.of(new LlmToolCall(id, new ScriptTool().name(),
                    "{\"code\":\"" + code + "\",\"description\":\"Run it\"}")), null), Usage.ZERO));
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
        private final NumenTool ask = new AskUserQuestionTool();

        @Override
        public List<NumenTool> all() {
            return List.of(script, ask);
        }

        @Override
        public NumenTool resolve(String name) {
            return script.name().equals(name) ? script : ask.name().equals(name) ? ask : null;
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
        LibraryHub hub = LibraryHub.local(library, Switches.inMemory(), List.of(), Set.of());
        host = new LoopHost(new LoopHost.Parts(HER, OWNER, home, clientMain::add, pipe.client(), model, SCRIPT_ONLY,
                () -> false, () -> EventQueue.DEFAULT_LEVEL, bound -> "a test persona",
                bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), id -> "主人", "test"));
        host.bind(com.dwinovo.numen.api.mind.Slot.PERSONA,
                new Bound(OWNER, new com.dwinovo.numen.api.library.EntryId("my", "test")));
        seats.bodyEntered(HER);
        Opened opened = pipe.client().open(HER, 0).get(5, TimeUnit.SECONDS);
        host.attach(opened, Catalog.fromJson(pipe.client().catalog(HER).get(5, TimeUnit.SECONDS)),
                pipe.client().openConversation(HER).get(5, TimeUnit.SECONDS));
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
        assertTrue(model.asked.getFirst().systemPrompt().contains("# Persona"), "the prompt is composed on the client");
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
    void aProgramTheModelWritesRunsAtTheBodyAndItsReceiptComesBack() throws Exception {
        model.program("call-1", "return 1").say("挖好了");

        host.submitPrompt("挖个坑");
        until("the program reached the body", () -> world.programs.running.containsKey("call-1"));

        world.programs.finish("call-1", new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), null, 0, java.util.Map.of()));
        until("the receipt woke the second request", () -> model.asked.size() == 2);
    }

    private static final UUID FRIEND = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    @Test
    void aFriendsWhisperIsAnsweredAsAWhisperToHimAndTheProgramsThatFollowAreHis() throws Exception {
        world.sharedWith.put(HER, Set.of(FRIEND));
        model.program("call-2", "return 1").say("在的");
        com.dwinovo.numen.api.talk.Spoken whisper = new com.dwinovo.numen.api.talk.Spoken(FRIEND,
                com.dwinovo.numen.api.talk.Venue.WHISPER, null);

        seats.publish(HER, new EventQueue.Entry(EventTypes.QUERY,
                EventQueue.query("你好", 6000, "player@abcdef (Friend)", "whisper"), 5L, false, null, null, "player@abcdef (Friend)"), whisper);

        until("her program reached the body", () -> world.programs.running.containsKey("call-2"));
        assertEquals(FRIEND, world.programs.keepers.get("call-2"), "the program acts for the one she is answering");
        world.programs.finish("call-2", new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), null, 0, java.util.Map.of()));
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
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), null, 0, java.util.Map.of()));
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

    private static final String QUESTION = "{\"questions\":[{\"question\":\"Which furnace?\",\"header\":\"Furnace\","
            + "\"options\":[{\"label\":\"Blast\",\"description\":\"fast\"},{\"label\":\"Smoker\",\"description\":\"food\"}]}]}";

    @Test
    void aQuestionHangsUntilTheOwnerAnswersHerLineReadsWaitingAndTheAnswerIsTheCallsResult() throws Exception {
        model.ask("ask-1", QUESTION).say("好的");

        host.submitPrompt("烧点东西");
        until("the question is waiting for the owner", () -> host.questions().pending() != null);
        assertEquals(new com.dwinovo.numen.api.agent.loop.LoopStatus.Doing(
                com.dwinovo.numen.api.data.ModLanguageData.Keys.ACTIVITY_AWAITING_ANSWER, List.of()), host.activity());
        assertEquals(1, model.asked.size(), "she does not ask the model again while she waits");

        host.questions().pending().pick(0);
        until("the answer woke the next request", () -> model.asked.size() == 2);
        String result = model.asked.get(1).messages().stream()
                .filter(m -> m instanceof com.dwinovo.numen.api.agent.llm.ConvoState.Msg.Tool)
                .map(m -> ((com.dwinovo.numen.api.agent.llm.ConvoState.Msg.Tool) m).content()).findFirst().orElse("");
        assertTrue(com.dwinovo.numen.api.agent.llm.ToolOutcome.modelText(result).contains("\"Which furnace?\"=\"Blast\""),
                "the answer is in the call's result: " + result);
    }

    @Test
    void theStopButtonTakesAPendingQuestionDownThroughTheCutOffAndTheModelIsToldOfTheCutOffNotAnAnswer() throws Exception {
        model.ask("ask-2", QUESTION).say("好的");

        host.submitPrompt("烧点东西");
        until("the question is waiting for the owner", () -> host.questions().pending() != null);

        host.abort();
        assertEquals(null, host.questions().pending(), "the panel is gone");
        assertEquals(null, host.activity(), "she is no longer waiting");
        assertEquals(1, model.asked.size());
    }

    @Test
    void duringAChannelVisitAQuestionIsRefusedAndTheToolListStaysTheSame() throws Exception {
        model.ask("ask-3", QUESTION).say("好的");
        host.visiting(() -> true);

        host.submitPrompt("在吗");
        until("the refusal woke the next request", () -> model.asked.size() == 2);

        assertEquals(null, host.questions().pending(), "nothing is shown at the game");
        assertEquals(model.asked.get(0).tools().stream().map(t -> t.name()).toList(),
                model.asked.get(1).tools().stream().map(t -> t.name()).toList(), "the cached tool list is not touched");
        String result = model.asked.get(1).messages().stream()
                .filter(m -> m instanceof com.dwinovo.numen.api.agent.llm.ConvoState.Msg.Tool)
                .map(m -> ((com.dwinovo.numen.api.agent.llm.ConvoState.Msg.Tool) m).content()).findFirst().orElse("");
        assertTrue(com.dwinovo.numen.api.agent.llm.ToolOutcome.failed(result), result);
        assertTrue(result.contains("chat channel"), result);
    }

    /** 同一只同伴在另一台电脑上的大脑:家是另一个目录,经另一条连接坐上她的座位,读服务端上她的对话记录。 */
    private LoopHost anotherComputer() throws Exception {
        pipe.disconnect();   // 原来那台电脑断线,她的座位上没人了
        SeatPipe second = SeatPipe.connect(seats, OWNER, clientMain::add, THREADS);
        Io io = NumenIo.inline("test");
        LibraryHub hub = LibraryHub.local(new Home(dir.resolve("library2"), io), Switches.inMemory(), List.of(), Set.of());
        LoopHost fresh = new LoopHost(new LoopHost.Parts(HER, OWNER, new Home(dir.resolve("her2"), io), clientMain::add,
                second.client(), model, SCRIPT_ONLY, () -> false, () -> EventQueue.DEFAULT_LEVEL, bound -> "a test persona",
                bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), id -> "主人", "test"));
        Opened opened = second.client().open(HER, 0).get(5, TimeUnit.SECONDS);
        fresh.attach(opened, Catalog.fromJson(second.client().catalog(HER).get(5, TimeUnit.SECONDS)),
                second.client().openConversation(HER).get(5, TimeUnit.SECONDS));
        return fresh;
    }

    @Test
    void herConversationLivesOnTheServerSoABrainOnAnotherComputerCarriesOnFromIt() throws Exception {
        model.say("好的").say("接着聊");
        host.submitPrompt("你好呀");
        until("the first turn is recorded on the server", () -> world.transcripts()
                .read(world.conversation(HER), rows -> rows.size()).join() >= 2);

        LoopHost fresh = anotherComputer();
        fresh.submitPrompt("还记得我吗");
        long deadline = System.nanoTime() + 3_000_000_000L;
        while (model.asked.size() < 2 && System.nanoTime() < deadline) {
            for (Runnable task; (task = clientMain.poll()) != null; ) {
                task.run();
            }
            fresh.tick();
            Thread.sleep(5);
        }

        assertEquals(2, model.asked.size(), "the second brain asked the model");
        String context = model.asked.get(1).messages().stream().map(Object::toString).reduce("", String::concat);
        assertTrue(context.contains("你好呀") && context.contains("好的"), "the earlier conversation came with the seat: " + context);
    }

    @Test
    void aGoalLivesInTheRecordSoTheNextBrainStillHasIt() throws Exception {
        host.setGoal(com.dwinovo.numen.api.agent.goal.GoalState.of("挖 64 个铁", 1_000L), "/goal 挖 64 个铁");
        model.say("好,这就去");
        until("the goal row is on the server", () -> world.transcripts().read(world.conversation(HER),
                rows -> com.dwinovo.numen.api.agent.llm.Rows.goal(com.dwinovo.numen.api.agent.llm.Rows.open(rows).goal()) != null)
                .join());

        LoopHost fresh = anotherComputer();

        assertEquals("挖 64 个铁", fresh.goal().objective());
    }

    @Test
    void theOwnersWordsAreMarkedWithWhoSaidThemEvenWithASingleOwnerAndTheRowRecordsIt() throws Exception {
        model.say("好的");
        host.submitPrompt("你好呀");
        until("the first turn is recorded on the server", () -> world.transcripts()
                .read(world.conversation(HER), rows -> rows.size()).join() >= 2);

        com.google.gson.JsonObject row = world.transcripts().read(world.conversation(HER), rows -> rows.getFirst()).join();

        assertEquals("player@000000", row.get("from").getAsString());
        assertTrue(model.asked.getFirst().messages().stream().anyMatch(m -> m.toString().contains("from=\"player@000000 (主人)\"")),
                "the model is told who is speaking");
    }
}
