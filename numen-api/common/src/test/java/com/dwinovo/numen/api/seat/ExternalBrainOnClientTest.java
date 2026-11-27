package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.Mind;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mcp.server.ExternalHands;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mind.ClientBrains;
import com.dwinovo.numen.api.mind.ConsentVenues;
import com.dwinovo.numen.api.mind.LoopHands;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.mcp.client.HttpClientTransport;
import com.dwinovo.numen.mcp.client.McpClient;
import com.dwinovo.numen.mcp.protocol.CallToolResult;
import com.dwinovo.numen.mcp.protocol.Content;
import com.dwinovo.numen.mcp.protocol.ElicitResult;
import com.dwinovo.numen.mcp.protocol.Implementation;
import com.dwinovo.numen.mcp.server.McpToken;
import com.dwinovo.numen.script.ScriptCall;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 外部大脑的端点在主人的客户端上:真的 HTTP 端点({@link McpMode})、真的 MCP 客户端扮外部大脑与渠道桥,经 {@link LoopHands} 驱动
 * 同一个 {@link LoopHost},大脑经真的座位表({@link Seats})坐在假的服务端世界({@link StubSeatWorld})的座位上。
 * 谁在驾驶只在客户端一处定,服务端不知道;征询只推给主人的客户端,外部大脑撞上"问"得到的是同一条。
 */
class ExternalBrainOnClientTest {

    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** 按事先排好的回复答的"模型":记下每次收到的请求;{@code holding} 时回复先扣着,等 {@link #release}。 */
    private static final class Scripted implements Mind {
        final Deque<ModelOutcome> replies = new ArrayDeque<>();
        final List<ModelRequest> asked = new CopyOnWriteArrayList<>();
        volatile boolean holding;
        private Runnable held;

        Scripted say(String text) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
            return this;
        }

        void release() {
            Runnable run = held;
            held = null;
            if (run != null) {
                run.run();
            }
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
            ModelOutcome reply = replies.poll();
            Runnable answer = () -> onDone.accept(reply);
            if (holding) {
                held = answer;
            } else {
                answer.run();
            }
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

    private final java.util.Queue<Runnable> clientMain = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final Thread testThread = Thread.currentThread();
    private final List<String> answered = new CopyOnWriteArrayList<>();
    private final List<String> summoned = new CopyOnWriteArrayList<>();

    private StubSeatWorld world;
    private Seats seats;
    private SeatPipe pipe;
    private Scripted model;
    private ClientBrains brains;
    private ConsentVenues venues;
    private McpMode mode;
    private LoopHost host;
    private final List<McpClient> clients = new ArrayList<>();
    private int port;

    @BeforeEach
    void seat() throws Exception {
        world = new StubSeatWorld().createdBy(HER, OWNER);
        seats = new Seats(world, THREADS);
        pipe = SeatPipe.connect(seats, OWNER, clientMain::add, THREADS);
        model = new Scripted();
        Io io = NumenIo.inline("test");
        Home library = new Home(dir.resolve("library"), io);
        LibraryHub hub = LibraryHub.local(library, Switches.inMemory(), List.of(), Set.of());
        venues = new ConsentVenues((companion, id, decision, note) -> answered.add(companion + "#" + id + ":" + decision + ":" + note));
        brains = new ClientBrains(pipe.client(), clientMain::add, companion -> {
            LoopHost made = new LoopHost(new LoopHost.Parts(companion, OWNER, new Home(dir.resolve("her"), io),
                    clientMain::add, pipe.client(), model, SCRIPT_ONLY, () -> mode.driving(companion),
                    () -> EventQueue.DEFAULT_LEVEL, bound -> "a test persona",
                    bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), id -> "主人"));
            made.bind(com.dwinovo.numen.api.mind.Slot.PERSONA,
                    new Bound(OWNER, new com.dwinovo.numen.api.library.EntryId("my", "test")));
            return made;
        });
        LoopHands hands = new LoopHands(brains, clientMain::add, () -> Thread.currentThread() == testThread, SCRIPT_ONLY,
                () -> List.of(new ExternalHands.Companion(HER, "Her")), new LoopHands.Actions() {
                    @Override
                    public boolean summon(String name) {
                        summoned.add("+" + name);
                        return true;
                    }

                    @Override
                    public boolean dismiss(UUID companion) {
                        summoned.add("-" + companion);
                        return true;
                    }
                }, venues);
        mode = new McpMode(new Home(dir.resolve("mcp"), io), OWNER, hands, venues, "9.9.9");
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        assertTrue(mode.applySettings("127.0.0.1", port, 30, List.of()) && mode.setEnabled(true), String.valueOf(mode.lastError()));
        seats.bodyEntered(HER);
        brains.sit(Set.of(HER));
        host = brains.get(HER);
        until("her seat is open", () -> host.catalog() != com.dwinovo.numen.api.seat.Catalog.EMPTY);
    }

    @AfterEach
    void close() {
        clients.forEach(McpClient::close);
        mode.shutdown();
        brains.leave();
    }

    /** 大脑一刻一刻地走,座位上来的回调在它的线程上做,直到条件成立;五秒还不成立就是没发生。 */
    private void until(String what, java.util.function.BooleanSupplier done) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (!done.getAsBoolean() && System.nanoTime() < deadline) {
            for (Runnable task; (task = clientMain.poll()) != null; ) {
                task.run();
            }
            brains.tick();
            mode.tick();
            Thread.sleep(5);
        }
        assertTrue(done.getAsBoolean(), what);
    }

    /** 端点自己的线程上等的那些调用:在别的线程上发,测试线程替客户端主线程走。 */
    private <T> T meanwhile(java.util.concurrent.Callable<T> call) throws Exception {
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                return call.call();
            } catch (Exception failed) {
                throw new RuntimeException(failed);
            }
        });
        until("the call came back", future::isDone);
        return future.get();
    }

    private McpClient connect(McpToken.Role role, McpClient.Elicitor elicitor) throws Exception {
        String secret = mode.tokens().issue(OWNER, role, McpToken.Scope.ALL, "", 0).secret();
        HttpClientTransport transport = new HttpClientTransport(URI.create("http://127.0.0.1:" + port + "/mcp"),
                Duration.ofSeconds(10), new HttpClientTransport.Authorization() {
                    @Override
                    public Map<String, String> headers() {
                        return Map.of("Authorization", "Bearer " + secret);
                    }

                    @Override
                    public boolean remedy(int status, List<String> wwwAuthenticate) {
                        return false;
                    }
                });
        McpClient client = meanwhile(() -> {
            McpClient opened = McpClient.connect(transport, new Implementation("test-client", null, "1", null, List.of()),
                    elicitor, () -> { }, Duration.ofSeconds(30), Duration.ofSeconds(30));
            opened.listTools();
            return opened;
        });
        clients.add(client);
        return client;
    }

    private String call(McpClient client, String tool, JsonObject args) throws Exception {
        CallToolResult result = meanwhile(() -> client.callTool(tool, args));
        StringBuilder text = new StringBuilder();
        for (Content block : result.content()) {
            text.append(((Content.Text) block).text()).append('\n');
        }
        return text.toString();
    }

    private static JsonObject args(String key, Object value) {
        JsonObject o = new JsonObject();
        o.addProperty("companion", "Her");
        if (key != null) {
            if (value instanceof Number n) {
                o.addProperty(key, n);
            } else {
                o.addProperty(key, String.valueOf(value));
            }
        }
        return o;
    }

    @Test
    void aBrainTokenConnectingTakesTheWheelAndLeavingGivesItBackAndTheServerNeverKnows() throws Exception {
        McpClient client = connect(McpToken.Role.BRAIN, null);

        until("she is driven from outside", () -> mode.driving(HER) && host.status().hold() == Hold.EXTERNAL);
        model.say("不该说");
        host.submitPrompt("你好");
        for (int i = 0; i < 10; i++) {
            until("a few ticks", () -> true);
        }
        assertTrue(model.asked.isEmpty(), "the built-in brain does not think while an outside brain drives her");

        client.close();
        clients.clear();
        until("the wheel is back", () -> !mode.driving(HER));
        until("she answers what waited", () -> model.asked.size() == 1);
    }

    @Test
    void anOutsideBrainReadsTheOwnersWordsAndRunsAProgramThroughTheSameSeat() throws Exception {
        McpClient client = connect(McpToken.Role.BRAIN, null);
        until("she is driven from outside", () -> mode.driving(HER));
        host.submitPrompt("挖个坑");

        JsonObject poll = args("wait_seconds", 5);
        String events = call(client, "get_events", poll);
        assertTrue(events.contains("挖个坑"), events);
        assertTrue(events.contains("<runtime_state>"), "the receipt ends with her state: " + events);

        JsonObject lua = args("code", "return 1 + 1");
        lua.addProperty("description", "Add one and one");
        CompletableFuture<String> running = CompletableFuture.supplyAsync(() -> {
            try {
                return call(client, new ScriptTool().name(), lua);
            } catch (Exception failed) {
                throw new RuntimeException(failed);
            }
        });
        until("the program reached the body", () -> world.programs.running.keySet().stream().anyMatch(id -> id.startsWith("ext-")));
        String id = world.programs.running.keySet().stream().filter(k -> k.startsWith("ext-")).findFirst().orElseThrow();
        world.programs.finish(id, new Program.Outcome("{\"success\":true,\"message\":\"done\"}",
                new ScriptCall.Ending(ScriptCall.Status.OK, null), List.of(), null, 0, java.util.Map.of()));
        until("the receipt came back", running::isDone);
        assertTrue(running.get().contains("done"), running.get());
    }

    @Test
    void anOutsideBrainSpeaksAndListsHerAndSummonsThroughTheGameSide() throws Exception {
        McpClient client = connect(McpToken.Role.BRAIN, null);

        assertTrue(call(client, "list_companions", new JsonObject()).contains("Her"));
        JsonObject say = args("text", "你好呀");
        assertTrue(call(client, "say", say).contains("said"));
        until("her words reached the seat", () -> !world.said.isEmpty() || !host.log().loadLines(10).isEmpty());
        JsonObject summon = new JsonObject();
        summon.addProperty("name", "Newcomer");
        call(client, "create_companion", summon);
        assertEquals(List.of("+Newcomer"), summoned);
    }

    @Test
    void aConsentTheOutsideBrainRunsIntoIsAskedOfThePersonAtItsClientAndAnsweredLikeTheCard() throws Exception {
        List<String> asked = new CopyOnWriteArrayList<>();
        connect(McpToken.Role.BRAIN, request -> {
            asked.add(request.message());
            JsonObject content = new JsonObject();
            content.addProperty("decision", "allow");
            return new ElicitResult(ElicitResult.Action.ACCEPT, content);
        });
        until("she is driven from outside", () -> mode.driving(HER));

        venues.show(new ConsentVenues.Offer(7, HER, "Her", "break 1 oak_log", List.of("break oak_log")));

        until("the person answered", () -> answered.size() == 1);
        assertTrue(asked.get(0).contains("break 1 oak_log"), asked.toString());
        assertEquals(List.of(HER + "#7:ALLOW_ONCE:"), answered);
        assertEquals(null, venues.pending(HER), "the card is gone once it is answered");
    }

    @Test
    void aChannelsWordsAreThoughtAboutHerAnswerAndHerConsentComeBackToThatChannel() throws Exception {
        McpClient channel = connect(McpToken.Role.CHANNEL, null);
        model.holding = true;
        model.say("在的");

        assertTrue(call(channel, "send", args("text", "在吗")).contains("sent"));
        until("she is thinking about it", () -> !model.asked.isEmpty());

        venues.show(new ConsentVenues.Offer(9, HER, "Her", "drop 1 diamond", List.of()));
        JsonObject wait = new JsonObject();
        wait.addProperty("wait_seconds", 5);
        JsonObject first = JsonParser.parseString(call(channel, "get_replies", wait)).getAsJsonObject();
        JsonArray items = first.getAsJsonArray("items");
        assertEquals("consent", items.get(0).getAsJsonObject().get("type").getAsString(), items.toString());

        JsonObject answer = args("id", 9);
        answer.addProperty("decision", "deny");
        answer.addProperty("note", "不行");
        assertTrue(call(channel, "answer_consent", answer).contains("answered"));
        assertEquals(List.of(HER + "#9:DENY:不行"), answered);

        model.release();
        until("her words are in the box", () -> true);
        StringBuilder later = new StringBuilder();
        for (int i = 0; i < 3 && !later.toString().contains("在的"); i++) {
            later.append(call(channel, "get_replies", wait));
        }
        assertTrue(later.toString().contains("在的"), later.toString());
    }

    @Test
    void aVisitEndsWhenHerRoundIsCutOffSoLaterThingsAreNotSentToThatChannel() throws Exception {
        McpClient channel = connect(McpToken.Role.CHANNEL, null);
        model.holding = true;
        model.say("在的");
        assertTrue(call(channel, "send", args("text", "在吗")).contains("sent"));
        until("she is thinking about it", () -> !model.asked.isEmpty());

        host.abort();   // the round is cut off, it never ends as Done

        venues.show(new ConsentVenues.Offer(11, HER, "Her", "drop 1 diamond", List.of()));
        JsonObject wait = new JsonObject();
        wait.addProperty("wait_seconds", 0);
        String replies = call(channel, "get_replies", wait);
        assertTrue(!replies.contains("consent"), "the visit is over, the channel gets nothing more: " + replies);
    }
}
