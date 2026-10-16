package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.script.ScriptEngine;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.brain.CompanionBrains;
import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.Conversations;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.History;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.OwnerInputs;
import com.dwinovo.numen.api.brain.Mind;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.mcp.server.McpConfig;
import com.dwinovo.numen.api.mcp.server.McpServer;
import com.dwinovo.numen.api.mcp.server.ServerActuator;

import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.dwinovo.numen.gametest.GameTestKit.programCall;
import static com.dwinovo.numen.gametest.GameTestKit.spawnAt;
import static com.dwinovo.numen.gametest.GameTestKit.succeedWhen;

/**
 * 服务端大脑({@link CompanionLoop})整条回路:主人的话进收件箱、内核调模型、模型派一段 Lua、程序在本进程里的
 * 服务端跑完、回执回到模型、最终回复进日志。模型是脚本化的({@link Scripted}),其余全是产品里的部件。
 */
public class BrainGameTests {

    /** 按事先排好的回复答的"模型";记下每次收到的请求。{@code holding} 时回复先扣着,等 {@link #release}。 */
    private static final class Scripted implements Mind {
        final Deque<ModelOutcome> replies = new ArrayDeque<>();
        final List<ModelRequest> asked = new ArrayList<>();
        boolean holding;
        private Runnable held;

        Scripted say(String text) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
            return this;
        }

        Scripted program(String code) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn("", List.of(programCall(code)), null), Usage.ZERO));
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
        public Cause unavailable(String provider) {
            return null;
        }

        @Override
        public LlmEndpoint endpoint(String provider) {
            return LlmEndpoint.unresolved("");
        }

        @Override
        public void ask(String provider, UUID companion, ModelRequest request, CancelToken cancel,
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

    /** 主人那一头:记下推给他的每一包事件。 */
    private static final class Owner implements CompanionLoops.Link {
        final List<Feed> events = new ArrayList<>();
        final List<String> told = new ArrayList<>();
        boolean client = true;

        @Override
        public boolean hasClient() {
            return client;
        }

        @Override
        public void send(UUID companion, List<Feed> batch) {
            events.addAll(batch);
        }

        @Override
        public void tell(UUID companion, String said) {
            told.add(said);
        }
    }

    /** 一只带服务端大脑的同伴:家在临时目录里,写在调用线程上直接做(用例读日志不必等车道)。 */
    private static final class Brain {
        final NumenPlayer companion;
        final Scripted mind = new Scripted();
        final Owner owner = new Owner();
        final Conversations rooms;
        final CompanionLoop loop;
        boolean ownerOnline = true;
        /** 驾驶席是不是在外接大脑手里。 */
        boolean driving;
        private final net.minecraft.server.MinecraftServer server;

        Brain(GameTestHelper helper, String name) throws java.io.IOException {
            this(helper, name, Files.createTempDirectory("numen-gametest-brain-"), null);
        }

        /** {@code dir} 里 {@code her/} 是她的家(可以已经有搬过来的东西)。 */
        Brain(GameTestHelper helper, String name, Path dir) throws java.io.IOException {
            this(helper, name, dir, null);
        }

        /** {@code ownerId} 非空时认他作主人(扮主人的真玩家在场时用)。 */
        Brain(GameTestHelper helper, String name, Path dir, UUID ownerId) throws java.io.IOException {
            this.server = helper.getLevel().getServer();
            this.companion = spawnAt(helper, name, new BlockPos(3, 2, 3), false);
            if (ownerId != null) {
                companion.setOwnerUuid(ownerId);
            }
            CompanionRegistry.get(server).put(companion.getUUID(), new CompanionRegistry.Entry(name,
                    companion.getOwnerUuid(), Level.OVERWORLD, companion.blockPosition()));
            Io io = NumenIo.inline("numen-gametest");
            NoteBook.init(uuid -> new Home(dir.resolve("memory"), io), () -> 0);
            this.rooms = Conversations.of(server, companion.getOwnerUuid());
            this.loop = CompanionBrains.create(server, companion.getUUID(), companion.getOwnerUuid(),
                    new Home(dir.resolve("her"), io), mind, owner, () -> ownerOnline, () -> driving);
        }

        void close() {
            CompanionLoops.dismissed(server, companion.getUUID());
            CompanionRegistry.get(server).remove(companion.getUUID());
            CompanionFactory.despawn(server, companion);
        }

        List<ConvoState.Msg> transcript() {
            return loop.log().loadLines(100).stream().map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::msg).toList();
        }

        /**
         * 对话本身:世界上别的用例里有人进服之类的事件会同时落进每只同伴的收件箱,随时可能注入进来,
         * 不属于这条用例要看的东西,所以不算。
         */
        List<ConvoState.Msg> conversation() {
            return transcript().stream()
                    .filter(m -> !(m instanceof ConvoState.Msg.User u && u.content().startsWith("<events>"))).toList();
        }
    }

    /** 主人的话 → 模型派一段程序 → 程序在服务端跑 → 回执回到模型 → 最终回复,整条回路都落进她的日志。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void owner_words_run_the_whole_loop_on_the_server(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_thinker");
        her.mind.program("return 1 + 1").say("算好了:2");

        her.loop.submitPrompt("算一下 1 + 1");

        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 2, "the model was asked " + her.mind.asked.size() + " time(s)");
            List<ConvoState.Msg> t = her.conversation();
            helper.assertTrue(t.size() == 4 && t.get(0) instanceof ConvoState.Msg.User
                            && t.get(1) instanceof ConvoState.Msg.Assistant a && a.turn().hasToolCalls()
                            && t.get(2) instanceof ConvoState.Msg.Tool && t.get(3) instanceof ConvoState.Msg.Assistant,
                    "the log does not hold user, call, receipt, reply: " + t);
            ConvoState.Msg.Tool receipt = (ConvoState.Msg.Tool) t.get(2);
            helper.assertTrue(receipt.content().contains("2"), "the receipt does not carry the program's value: "
                    + receipt.content());
            helper.assertTrue(her.mind.asked.get(1).messages().stream().anyMatch(m -> m instanceof ConvoState.Msg.Tool),
                    "the second request did not carry the receipt");
            helper.assertTrue(Files.isRegularFile(her.loop.log().file()), "nothing was written to her home");
            helper.assertTrue(her.owner.events.stream().anyMatch(e -> e instanceof Feed.Replied r
                            && r.content().equals("算好了:2")), "the owner's client was not told her reply: " + her.owner.events);
            helper.assertTrue(her.owner.events.stream().filter(e -> e instanceof Feed.Line).count()
                            == her.loop.log().loadLines(100).size(),
                    "the owner's client did not get every log line: " + her.owner.events);
            helper.assertTrue(her.owner.events.get(her.owner.events.size() - 1) instanceof Feed.Status s
                            && s.state().phase() == null && s.state().hold() == null,
                    "the last thing the client hears is not that she is idle: " + her.owner.events);
            her.close();
        });
    }

    /** 主人不在线时事件照收、不开 run;他回来,攒着的一起处理。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_away_owner_leaves_the_events_waiting_in_her_inbox(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_waiter");
        her.ownerOnline = false;
        her.mind.say("欢迎回来");

        NumenEvents.emit(her.companion, EventTypes.TASK_FINISHED, null, "矿挖完了", true);

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TASK_FINISHED)
                            && e.id() != null), "the event did not reach her inbox: " + her.loop.inbox().entries());
            helper.assertTrue(her.loop.status().hold() == Hold.AWAY, "not waiting: " + her.loop.status().hold());
            helper.assertTrue(her.mind.asked.isEmpty(), "she thought while her owner was away");
            her.ownerOnline = true;
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 1, "she did not take up the waiting event");
            helper.assertTrue(her.loop.status().hold() == null, "still held: " + her.loop.status().hold());
            her.close();
        });
    }

    /** 死着时不开 run;复活时死亡的叙事随攒着的输入一起走。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void death_freezes_the_loop_and_the_respawn_tells_her_how_she_died(GameTestHelper helper)
            throws Exception {
        Brain her = new Brain(helper, "gametest_mortal");
        her.mind.say("我回来了");

        her.loop.onDied("摔死了");
        her.loop.submitPrompt("在吗");
        helper.assertTrue(her.loop.status().hold() == Hold.DEAD, "not frozen: " + her.loop.status().hold());
        helper.assertTrue(her.mind.asked.isEmpty(), "she thought while dead");

        her.loop.onRespawned(null);

        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 1, "the respawn did not wake her");
            String told = her.mind.asked.get(0).messages().stream()
                    .filter(m -> m instanceof ConvoState.Msg.User)
                    .map(m -> ((ConvoState.Msg.User) m).content()).reduce("", String::concat);
            helper.assertTrue(told.contains("摔死了") && told.contains("在吗"),
                    "the first request after the respawn lacks the death or the waiting words: " + told);
            her.close();
        });
    }

    /** 主人按停止:在飞的回复作废,日志记下切断点,没有新的 run,直到主人再开口。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void stop_voids_the_reply_in_flight_until_the_owner_speaks_again(GameTestHelper helper)
            throws Exception {
        Brain her = new Brain(helper, "gametest_stopped");
        her.mind.holding = true;
        her.mind.say("这句不该被听到").say("好的");

        her.loop.submitPrompt("去挖矿");
        helper.assertTrue(her.mind.asked.size() == 1, "she did not start thinking");
        her.loop.abort();
        her.mind.release();   // 作废之后到的回复:没有人要了

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(her.loop.status().hold() == Hold.OWNER_STOP, "not stopped: " + her.loop.status().hold());
            helper.assertTrue(her.transcript().stream().noneMatch(m -> m instanceof ConvoState.Msg.Assistant),
                    "the voided reply was recorded: " + her.transcript());
            helper.assertTrue(her.transcript().stream().anyMatch(m -> m instanceof ConvoState.Msg.Halt),
                    "the cut was not recorded: " + her.transcript());
            her.mind.holding = false;
            her.loop.submitPrompt("继续");
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 2, "the owner's next word did not wake her");
            helper.assertTrue(her.loop.status().hold() == null, "still stopped");
            her.close();
        });
    }

    /** 历史按游标一页一页往前翻,翻完正好是整份记录,一行不重不漏。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void history_pages_walk_back_by_cursor(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_pager");
        her.mind.program("return 1 + 1").say("算好了:2");
        her.loop.submitPrompt("算一下 1 + 1");

        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 2 && her.conversation().size() == 4, "the loop has not finished");
            var conv = her.rooms.find(her.companion.getUUID().toString());
            List<String> ids = new ArrayList<>();
            String cursor = null;
            int pages = 0;
            History.Page page;
            do {
                page = History.page(her.rooms, conv, cursor, 3).join();
                List<String> older = new ArrayList<>();
                page.entries().forEach(e -> older.add(e.ids().get(e.companion())));
                ids.addAll(0, older);
                cursor = page.cursor();
                pages++;
            } while (page.more());
            helper.assertTrue(pages >= 2, "the record fit on one page of three: " + ids);
            helper.assertTrue(ids.equals(her.loop.log().loadLines(100).stream()
                            .map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::id).toList()),
                    "the pages do not add up to the whole record: " + ids);
            her.close();
        });
    }

    /** 主人经客户端说话:过归属检查,进她的收件箱;别人名下的同伴、不存在的会话一概不收。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_owners_words_reach_only_his_own_companion(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_mine");
        her.mind.say("在的");
        var server = helper.getLevel().getServer();
        UUID stranger = UUID.randomUUID();

        helper.assertTrue(OwnerInputs.apply(server, stranger, new OwnerInput.Say(her.companion.getUUID().toString(),
                "我不是你主人")).problem() != null, "a stranger's words were accepted");
        helper.assertTrue(OwnerInputs.apply(server, stranger, new OwnerInput.Compact(her.companion.getUUID()))
                .problem() != null, "a stranger's command was accepted");
        helper.assertTrue(her.mind.asked.isEmpty(), "she thought about a stranger's words");

        var applied = OwnerInputs.apply(server, her.companion.getOwnerUuid(),
                new OwnerInput.Say(her.companion.getUUID().toString(), "你在吗"));
        helper.assertTrue(applied.problem() == null, "the owner's words were refused: " + applied.problem());

        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 1, "the owner's words did not wake her");
            her.close();
        });
    }

    /** 旧版本留在客户端的对话、收件箱、目标搬进世界之后,她醒来还记得。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_companion_with_migrated_data_wakes_up_remembering_it(GameTestHelper helper)
            throws Exception {
        Path dir = Files.createTempDirectory("numen-gametest-migrate-");
        Path legacy = dir.resolve("config").resolve("companions").resolve("her");
        var old = com.dwinovo.numen.api.agent.llm.ConvoLog.atFile(legacy.resolve("chat.jsonl"));
        old.append(new ConvoState.Msg.User("<query>记得我吗</query>"), null);
        old.append(new ConvoState.Msg.Assistant(new AssistantTurn("记得", List.of(), null)), null);
        var waiting = com.dwinovo.numen.api.agent.inbox.JsonlJournal.atFile(legacy.resolve("inbox.jsonl"));
        waiting.save(List.of(new com.dwinovo.numen.api.agent.inbox.EventQueue.Entry(EventTypes.TASK_FINISHED,
                "<event>矿挖完了</event>", 1L, false)));
        var goal = new com.dwinovo.numen.api.store.JsonDoc(legacy.resolve("goal.json"),
                com.dwinovo.numen.api.store.JsonDoc.Format.of(1), NumenIo.inline("test"));
        goal.write(com.dwinovo.numen.api.agent.goal.GoalState.of("挖 64 个铁", 0).toJson());

        helper.assertTrue(com.dwinovo.numen.api.brain.LegacyMigration.companion(legacy, dir.resolve("her")),
                "nothing was migrated");
        Brain her = new Brain(helper, "gametest_rememberer", dir);

        helper.assertTrue(her.transcript().size() == 2 && her.transcript().get(0) instanceof ConvoState.Msg.User,
                "the migrated conversation is not there: " + her.transcript());
        helper.assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TASK_FINISHED)),
                "the migrated inbox is not there");
        helper.assertTrue(her.loop.goal() != null && her.loop.goal().objective().equals("挖 64 个铁"),
                "the migrated goal is not there");
        her.close();
        helper.succeed();
    }

    /** 装了客户端的主人报到:得到回答(协议版本、是不是管理员),并补上名下大脑此刻的样子与会话列表,整条路经真的发包。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_client_that_says_hello_is_answered_and_caught_up(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_greeter");
        Brain her = new Brain(helper, "gametest_greeted", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID());
        GameTestKit.received(owner);

        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);

        var sent = GameTestKit.received(owner);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.HelloAckPayload a
                        && a.protocol() == com.dwinovo.numen.api.brain.Presence.PROTOCOL && !a.admin()),
                "no hello answer for an ordinary player: " + sent);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.FeedPayload f
                        && f.companion().equals(her.companion.getUUID())
                        && f.events().stream().anyMatch(e -> e instanceof Feed.Status)),
                "her state was not caught up: " + sent);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.ConversationsPayload),
                "the conversation list was not sent");
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 协议版本对不上的客户端不被服务:回答照给,但不推任何她的东西。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_client_of_another_protocol_is_answered_but_not_served(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_mismatch");
        Brain her = new Brain(helper, "gametest_unserved", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID());
        GameTestKit.received(owner);

        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL + 1);

        var sent = GameTestKit.received(owner);
        helper.assertTrue(sent.size() == 1 && sent.get(0) instanceof com.dwinovo.numen.api.network.payload.HelloAckPayload,
                "an unserved client got more than the answer: " + sent);
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 普通玩家经协议改不了服务器上的模型档案和配置,回话说明原因;看到的档案表里没有 key。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_ordinary_player_cannot_change_the_servers_model_profiles(GameTestHelper helper)
            throws Exception {
        var player = GameTestKit.presentPlayer(helper, null, "gametest_commoner");
        helper.assertTrue(!com.dwinovo.numen.api.config.Authority.isAdmin(player), "the test player is an admin");
        int before = com.dwinovo.numen.api.agent.llm.ProviderLibrary.instance().list().size();
        GameTestKit.received(player);

        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "put",
                "{\"name\":\"偷偷加的\",\"provider\":\"openai\",\"api_key\":\"sk-stolen\"}");
        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.SERVER_CONFIG, "put",
                "{\"proxy\":\"evil:1\"}");

        var sent = GameTestKit.received(player);
        helper.assertTrue(sent.size() == 2 && sent.stream().allMatch(p ->
                        p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l && !l.problem().isEmpty()),
                "the refusals were not told: " + sent);
        helper.assertTrue(com.dwinovo.numen.api.agent.llm.ProviderLibrary.instance().list().size() == before,
                "a profile was added by an ordinary player");
        helper.assertTrue(!com.dwinovo.numen.api.platform.Services.CONFIG.getProxy().equals("evil:1"),
                "the proxy was changed by an ordinary player");
        GameTestKit.leave(player);
        helper.succeed();
    }

    /** 一次 MCP tools/call:进程内 HTTP 发 JSON-RPC,返回回执的第一段文本。 */
    private static String mcpCall(int port, String tool, JsonObject args) throws Exception {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args);
        JsonObject req = new JsonObject();
        req.addProperty("jsonrpc", "2.0");
        req.addProperty("id", 1);
        req.addProperty("method", "tools/call");
        req.add("params", params);
        var response = java.net.http.HttpClient.newHttpClient().send(java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create("http://127.0.0.1:" + port + "/mcp"))
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(req.toString())).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        return com.google.gson.JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonObject("result")
                .getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString();
    }

    private static int indexOf(List<ConvoState.Msg> messages, java.util.function.Predicate<ConvoState.Msg> match) {
        for (int i = 0; i < messages.size(); i++) {
            if (match.test(messages.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private static JsonObject mcpArgs(String companion, String key, String value) {
        JsonObject args = new JsonObject();
        args.addProperty("companion", companion);
        if (key != null) {
            args.addProperty(key, value);
        }
        return args;
    }

    /**
     * 外接大脑经进程内的 MCP 宿主驱动她:点名、取件(主人的话)、替她说话、跑程序。整段进她的日志,主人的客户端收到她说的话,
     * 内脑一轮都没开。外脑是另一条线程,和产品里一样阻塞着等回执。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_external_brain_drives_her_through_the_mcp_host(GameTestHelper helper) throws Exception {
        String name = "gametest_driven";
        Brain her = new Brain(helper, name);
        her.driving = true;
        int port;
        try (var probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        McpServer host = new McpServer(new McpConfig(true, McpConfig.LOOPBACK, port, "", 30, List.of(), false),
                new ServerActuator(helper.getLevel().getServer(), her.companion.getOwnerUuid()));
        host.start();
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread brain = new Thread(() -> {
            try {
                heard.add(mcpCall(port, "list_companions", new JsonObject()));
                JsonObject wait = mcpArgs(name, null, null);
                wait.addProperty("wait_seconds", 20);
                heard.add(mcpCall(port, "get_events", wait));
                heard.add(mcpCall(port, "say", mcpArgs(name, "text", "收到")));
                JsonObject program = ScriptTool.args("return 1 + 1");
                program.addProperty("companion", name);
                heard.add(mcpCall(port, ScriptEngine.IN_USE.toolName(), program));
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.set(true);
            }
        }, "gametest-external-brain");
        brain.setDaemon(true);
        brain.start();
        helper.runAfterDelay(10, () -> her.loop.submitPrompt("你好"));

        succeedWhen(helper, () -> {
            helper.assertTrue(done.get(), "the external brain has not finished: " + heard);
            host.stop();
            helper.assertTrue(failure.get() == null, "the external brain failed: " + failure.get());
            helper.assertTrue(heard.get(0).contains(name), "list_companions did not name her: " + heard.get(0));
            helper.assertTrue(heard.get(1).contains("你好"), "get_events did not hand over the owner's words: " + heard.get(1));
            helper.assertTrue(heard.get(2).equals("said"), "say was not accepted: " + heard.get(2));
            helper.assertTrue(heard.get(3).contains("2"), "the program's receipt lacks its value: " + heard.get(3));
            List<ConvoState.Msg> t = her.transcript();
            helper.assertTrue(t.get(0) instanceof ConvoState.Msg.User u && u.content().contains("你好"),
                    "the owner's words taken by the external brain are not in the log: " + t);
            int said = indexOf(t, m -> m instanceof ConvoState.Msg.Assistant a && a.turn().content().equals("收到"));
            int call = indexOf(t, m -> m instanceof ConvoState.Msg.Assistant a && a.turn().hasToolCalls());
            helper.assertTrue(0 < said && said < call && call < t.size() - 1 && t.get(call + 1) instanceof ConvoState.Msg.Tool,
                    "the log does not hold what she said, then the call and its receipt: " + t);
            helper.assertTrue(her.owner.events.stream().anyMatch(e -> e instanceof Feed.Replied r
                    && r.content().equals("收到")), "the owner's client was not told what she said");
            helper.assertTrue(her.mind.asked.isEmpty(), "her own brain thought while the external one drove");
            her.close();
        });
    }
}
