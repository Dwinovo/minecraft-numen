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
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.script.ScriptEngine;
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
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.api.mcp.server.McpToken;

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
    static final class Scripted implements Mind {
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
    static final class Owner implements CompanionLoops.Link {
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
        public void said(UUID companion, String said, com.dwinovo.numen.api.brain.Venue venue, String conversation) {
            told.add(said);
        }

        @Override
        public void activities(java.util.Map<UUID, com.dwinovo.numen.api.agent.loop.LoopStatus.Doing> doing) {
        }
    }

    /** 一只带服务端大脑的同伴:家在临时目录里,写在调用线程上直接做(用例读日志不必等车道)。 */
    static final class Brain {
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
            this(helper, name, dir, ownerId, null);
        }

        /** {@code link} 非空时用它当主人那一头(产品里真正的 {@code Replies.link}),否则是记下推送的 {@link #owner}。 */
        Brain(GameTestHelper helper, String name, Path dir, UUID ownerId, CompanionLoops.Link link)
                throws java.io.IOException {
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
                    new Home(dir.resolve("her"), io), mind, link == null ? owner : link, () -> ownerOnline,
                    () -> driving);
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

    /**
     * 主人清空她的记忆:对话日志与札记删掉,名册与身体不动,面板收到"清空了",并且如实告诉她;导出的 zip 在清空之前带着
     * 日志和札记。只有主人能做;她正在想事情的时候不清,什么也不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void wiping_her_memory_deletes_the_log_and_notes_tells_her_and_waits_until_she_is_idle(
            GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_forgetful");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();
        her.mind.holding = true;
        her.mind.say("好的");
        her.loop.submitPrompt("记一下铁在东边");
        com.dwinovo.numen.api.agent.memory.NoteBook.of(her.companion.getUUID())
                .write("iron", "铁在东边", "reference", "东边 30 格");

        var busy = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner,
                new com.dwinovo.numen.api.brain.OwnerInput.Wipe(her.companion.getUUID()));
        helper.assertTrue(busy.problem() != null, "she was wiped in the middle of a thought");
        helper.assertTrue(!com.dwinovo.numen.api.agent.memory.NoteBook.of(her.companion.getUUID()).index().isEmpty(),
                "a refused wipe deleted her notes");
        var stranger = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, UUID.randomUUID(),
                new com.dwinovo.numen.api.brain.OwnerInput.Wipe(her.companion.getUUID()));
        helper.assertTrue(stranger.problem() != null, "someone else wiped her");
        her.mind.holding = false;
        her.mind.release();

        GameTestKit.steps(helper).thenWaitUntil(() -> helper.assertTrue(
                her.conversation().size() == 2 && her.loop.status().phase() == null,
                "she has not finished: " + her.conversation())).thenExecute(() -> {
            byte[] zip = her.loop.exportMemory().join();
            java.util.Set<String> names = new java.util.HashSet<>();
            try (var in = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                for (var e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                    names.add(e.getName());
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            // 札记在她家的 memory/ 里一并打包由 MemoryExportTest 管;这里的测试脑家的布局不同,只看日志进了包
            helper.assertTrue(names.contains("chat.jsonl"), "export: " + names);

            var done = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner,
                    new com.dwinovo.numen.api.brain.OwnerInput.Wipe(her.companion.getUUID()));
            helper.assertTrue(done.problem() == null, "the wipe was refused: " + done.problem());
            helper.assertTrue(her.conversation().stream().noneMatch(m -> m instanceof ConvoState.Msg.User u
                    && u.content().contains("铁在东边")), "the old words are still in the log: " + her.conversation());
            helper.assertTrue(com.dwinovo.numen.api.agent.memory.NoteBook.of(her.companion.getUUID()).index().isEmpty(),
                    "her notes are still there");
            helper.assertTrue(CompanionRegistry.get(server).find(her.companion.getUUID()) != null,
                    "wiping her memory removed her from the roster");
            helper.assertTrue(her.loop.inbox().entries().stream().anyMatch(e -> e.text().contains("清空了你的记忆")),
                    "she was not told: " + her.loop.inbox().entries());
        }).thenWaitUntil(() -> helper.assertTrue(her.owner.events.stream().anyMatch(e -> e instanceof Feed.Wiped),
                "the panel was not told")).thenExecute(her::close).thenSucceed();
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

    /** 普通玩家经协议改不了服务器上的内置模型档案和配置,回话说明原因;自己的档案存得下,服主的 key 他读不到。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_ordinary_player_cannot_change_the_servers_model_profiles(GameTestHelper helper)
            throws Exception {
        var player = GameTestKit.presentPlayer(helper, null, "gametest_commoner");
        helper.assertTrue(!com.dwinovo.numen.api.config.Authority.isAdmin(player), "the test player is an admin");
        var builtin = com.dwinovo.numen.api.library.LibraryHub.of(helper.getLevel().getServer())
                .builtin(com.dwinovo.numen.api.agent.llm.ProviderLibrary.KIND);
        int before = builtin.items().size();
        GameTestKit.received(player);

        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "put",
                "{\"name\":\"偷偷加的\",\"group\":\"builtin\",\"provider\":\"openai\"}");
        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.SERVER_CONFIG, "put",
                "{\"proxy\":\"evil:1\"}");

        var sent = GameTestKit.received(player);
        helper.assertTrue(sent.size() == 2 && sent.stream().allMatch(p ->
                        p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l && !l.problem().isEmpty()),
                "the refusals were not told: " + sent);
        helper.assertTrue(builtin.items().size() == before, "a built-in profile was added by an ordinary player");
        helper.assertTrue(!com.dwinovo.numen.api.platform.Services.config().getProxy().equals("evil:1"),
                "the proxy was changed by an ordinary player");
        GameTestKit.leave(player);
        helper.succeed();
    }

    /**
     * 连通性检测要等一会儿才有结局:现在送的"在试"是一张不答任何请求的表,请求号跟着结局那一张回去,
     * 表单凭号认出这是它的答复。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_connection_test_is_answered_by_its_outcome_under_the_request_number(GameTestHelper helper) {
        var player = GameTestKit.presentPlayer(helper, null, "gametest_checker");
        GameTestKit.received(player);

        com.dwinovo.numen.api.brain.Replies.library(player, com.dwinovo.numen.api.brain.Libraries.PROVIDER_CHECK, "run",
                "{\"draft\":{\"provider\":\"moonshotai\"}}", 9);

        var told = new ArrayList<com.dwinovo.numen.api.network.payload.LibraryPayload>();
        succeedWhen(helper, () -> {
            GameTestKit.received(player).forEach(p -> {
                if (p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l) {
                    told.add(l);
                }
            });
            helper.assertTrue(told.size() == 2, "the test was not answered twice yet: " + told);
            helper.assertTrue(told.get(0).request() == 0 && told.get(0).entries().contains("checking"),
                    "the 'trying' table was taken for the answer: " + told.get(0));
            helper.assertTrue(told.get(1).request() == 9 && told.get(1).entries().contains("need_key"),
                    "the outcome did not come back under the request number: " + told.get(1));
            GameTestKit.leave(player);
        });
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
        McpMode mcp = McpMode.instance();
        helper.assertTrue(mcp.applySettings(McpConfig.LOOPBACK, port, 30, List.of()) && mcp.setEnabled(true),
                "the endpoint did not start: " + mcp.lastError());
        McpTestClient client = new McpTestClient(port, mcp.tokens().issue(her.companion.getOwnerUuid(),
                McpToken.Role.BRAIN, McpToken.Scope.ALL, "gametest", 0).secret());
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        Thread brain = new Thread(() -> {
            try {
                client.connect();
                heard.add(client.call("list_companions", new JsonObject()));
                JsonObject wait = mcpArgs(name, null, null);
                wait.addProperty("wait_seconds", 20);
                heard.add(client.call("get_events", wait));
                heard.add(client.call("say", mcpArgs(name, "text", "收到")));
                JsonObject program = ScriptTool.args("return 1 + 1");
                program.addProperty("companion", name);
                heard.add(client.call(ScriptEngine.IN_USE.toolName(), program));
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
            mcp.setEnabled(false);
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

    /** 群里 @ 谁谁醒,别人旁听:旁听是捎带的事件,不叫醒她。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void in_a_group_the_one_called_wakes_and_the_other_only_overhears(GameTestHelper helper)
            throws Exception {
        Brain a = new Brain(helper, "gametest_ga");
        Brain b = new Brain(helper, "gametest_gb", Files.createTempDirectory("numen-gametest-brain-"),
                a.companion.getOwnerUuid());
        a.mind.say("来了");
        var server = helper.getLevel().getServer();
        UUID owner = a.companion.getOwnerUuid();

        var pulled = OwnerInputs.apply(server, owner, new OwnerInput.Pull(a.companion.getUUID().toString(),
                b.companion.getUUID(), "gametest-group"));
        helper.assertTrue(pulled.problem() == null && pulled.conversationsChanged(), "the group was not made: " + pulled);
        var said = OwnerInputs.apply(server, owner, new OwnerInput.Say("gametest-group", "@gametest_ga 过来一下"));
        helper.assertTrue(said.problem() == null, "the words were refused: " + said.problem());

        succeedWhen(helper, () -> {
            helper.assertTrue(a.mind.asked.size() == 1, "the one called did not wake");
            helper.assertTrue(b.mind.asked.isEmpty(), "the one who only overheard woke up");
            helper.assertTrue(b.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                    && e.text().contains("过来一下")), "the other did not overhear: " + b.loop.inbox().entries());
            a.close();
            b.close();
        });
    }

    /** 渠道(桥接、弹幕)送来的输入和主人的话走同一个门:认归属、认类型。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_bridge_input_reaches_her_like_the_owners_words(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_bridged");
        her.mind.say("收到弹幕");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();

        helper.assertTrue(OwnerInputs.apply(server, owner, new OwnerInput.Emit(her.companion.getUUID(), "no_such_type",
                "x")).problem() != null, "an unregistered type was accepted");
        helper.assertTrue(OwnerInputs.apply(server, UUID.randomUUID(), new OwnerInput.Emit(her.companion.getUUID(),
                EventTypes.QUERY, "x")).problem() != null, "a stranger's input was accepted");
        var applied = OwnerInputs.apply(server, owner, new OwnerInput.Emit(her.companion.getUUID(), EventTypes.QUERY,
                "弹幕:加油"));
        helper.assertTrue(applied.problem() == null, "the owner's bridge was refused: " + applied.problem());

        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 1, "the bridged words did not wake her");
            helper.assertTrue(her.transcript().get(0) instanceof ConvoState.Msg.User u && u.content().contains("弹幕:加油"),
                    "the bridged words are not in her log: " + her.transcript());
            her.close();
        });
    }

    /** 外接大脑把驾驶席交还:驾驶期间攒下的话,内脑回来就接着答。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void handing_the_wheel_back_lets_her_answer_what_waited(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_handback");
        her.mind.say("我在");
        her.driving = true;
        her.loop.submitPrompt("在吗");

        helper.runAfterDelay(5, () -> {
            helper.assertTrue(her.mind.asked.isEmpty(), "she thought while an external brain drove");
            helper.assertTrue(her.loop.status().hold() == Hold.EXTERNAL, "not held: " + her.loop.status().hold());
            her.driving = false;
        });
        succeedWhen(helper, () -> {
            helper.assertTrue(her.mind.asked.size() == 1, "she did not take the wheel back");
            helper.assertTrue(her.loop.status().hold() == null, "still held: " + her.loop.status().hold());
            her.close();
        });
    }

    /**
     * 召唤只要名字,不设前提:她有服务端大脑,可是还没绑档案,主人跟她说话时她停在 BLOCKED、原因是"还没配模型档案";
     * 档案、人设、声线、皮肤是召唤之后经同一个入口绑上的,四项都记进她的绑定、穿上的皮肤落在名册里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void summoning_takes_only_a_name_and_the_four_bindings_come_afterwards(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_summoner");

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_born", false), owner);

        var born = server.getPlayerList().getPlayerByName("gametest_born");
        helper.assertTrue(born instanceof NumenPlayer, "the companion was not summoned");
        var brain = CompanionLoops.get(born.getUUID());
        helper.assertTrue(brain.isPresent(), "she has no server brain");
        for (var slot : com.dwinovo.numen.api.brain.Slot.values()) {
            helper.assertTrue(brain.get().bound(slot) == null, "she came with a " + slot + " already bound");
        }
        brain.get().submitPrompt("在吗");
        helper.runAfterDelay(20, () -> {
            helper.assertTrue(brain.get().status().hold() == Hold.BLOCKED, "not held: " + brain.get().status().hold());
            helper.assertTrue(com.dwinovo.numen.api.agent.loop.Cause.UNBOUND.equals(
                    brain.get().status().holdReason().code()), "wrong reason: " + brain.get().status().holdReason());

            var persona = com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID())
                    .create("gametest_persona", "你是测试员");
            var profiles = com.dwinovo.numen.api.brain.ModelServices.of(server).providers(owner.getUUID());
            var profile = profiles.create("gametest_profile",
                    new com.dwinovo.numen.api.agent.llm.ConnectionProfile("deepseek", null, "deepseek-chat", null,
                            "https://relay.example", null, null, 0, null, 0, null));
            var hub = com.dwinovo.numen.api.library.LibraryHub.of(server);
            var voiceId = com.dwinovo.numen.api.library.EntryId.mine("gametest_voice");
            hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().write(voiceId,
                    new com.dwinovo.numen.api.voice.VoiceProfile("", "openai", "", "", "m", "alex", "", "", "", 1f)
                            .write());
            var skinId = com.dwinovo.numen.api.library.EntryId.mine("gametest_skin");
            hub.library(com.dwinovo.numen.api.skin.SkinProfile.KIND, owner.getUUID()).mine().write(skinId,
                    new com.dwinovo.numen.api.skin.SkinProfile("classic", "gametest-value", "gametest-sig", null).write());

            var slots = com.dwinovo.numen.api.brain.Slot.values();
            String[] entries = {profile.id(), persona.id(), voiceId.toString(), skinId.toString()};
            for (int i = 0; i < slots.length; i++) {
                var applied = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner.getUUID(),
                        new com.dwinovo.numen.api.brain.OwnerInput.Bind(born.getUUID(), slots[i], entries[i]));
                helper.assertTrue(applied.problem() == null, slots[i] + " was refused: " + applied.problem());
                helper.assertTrue(entries[i].equals(brain.get().bound(slots[i])), slots[i] + " not bound");
            }
            helper.assertTrue("gametest-value".equals(CompanionRegistry.get(server).find(born.getUUID()).skinValue()),
                    "the skin was not put on her");
            var refused = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner.getUUID(),
                    new com.dwinovo.numen.api.brain.OwnerInput.Bind(born.getUUID(),
                            com.dwinovo.numen.api.brain.Slot.VOICE, "my:nowhere"));
            helper.assertTrue(refused.problem() != null, "an entry that is not in the library was bound");
            helper.assertTrue(voiceId.toString().equals(brain.get().bound(com.dwinovo.numen.api.brain.Slot.VOICE)),
                    "a refused bind changed her voice");

            var unbound = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner.getUUID(),
                    new com.dwinovo.numen.api.brain.OwnerInput.Bind(born.getUUID(),
                            com.dwinovo.numen.api.brain.Slot.SKIN, null));
            helper.assertTrue(unbound.problem() == null && brain.get().bound(com.dwinovo.numen.api.brain.Slot.SKIN) == null
                    && CompanionRegistry.get(server).find(born.getUUID()).skinValue().isEmpty(), "unbinding the skin");

            com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(born.getUUID()));
            profiles.remove(profile.id());
            com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID()).remove(persona.id());
            hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().delete(voiceId);
            hub.library(com.dwinovo.numen.api.skin.SkinProfile.KIND, owner.getUUID()).mine().delete(skinId);
            GameTestKit.leave(owner);
            helper.succeed();
        });
    }

    /**
     * 内置皮肤是所有人共用的:两位主人名下的同伴都穿着同一条,管理员改了它,两只身上都换成新的——绑定与身体只认库里这一条。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void changing_a_builtin_skin_re_dresses_everyone_wearing_it(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        UUID ownerA = UUID.randomUUID();
        UUID ownerB = UUID.randomUUID();
        Brain a = new Brain(helper, "gametest_wearer_a", Files.createTempDirectory("numen-gametest-brain-"), ownerA);
        Brain b = new Brain(helper, "gametest_wearer_b", Files.createTempDirectory("numen-gametest-brain-"), ownerB);
        var shelf = com.dwinovo.numen.api.library.LibraryHub.of(server)
                .builtin(com.dwinovo.numen.api.skin.SkinProfile.KIND);
        var id = new com.dwinovo.numen.api.library.EntryId("server", "gametest_shared");
        shelf.write(id, new com.dwinovo.numen.api.skin.SkinProfile("classic", "shared-v1", "sig", null).write());
        var slot = com.dwinovo.numen.api.brain.Slot.SKIN;
        helper.assertTrue(com.dwinovo.numen.api.brain.CompanionBinding.apply(server, ownerA, a.companion.getUUID(), slot,
                id.toString()) == null, "the first wearer was refused");
        helper.assertTrue(com.dwinovo.numen.api.brain.CompanionBinding.apply(server, ownerB, b.companion.getUUID(), slot,
                id.toString()) == null, "the second wearer was refused");
        var registry = CompanionRegistry.get(server);
        helper.assertTrue("shared-v1".equals(registry.find(a.companion.getUUID()).skinValue())
                && "shared-v1".equals(registry.find(b.companion.getUUID()).skinValue()), "they do not wear it yet");

        shelf.write(id, new com.dwinovo.numen.api.skin.SkinProfile("classic", "shared-v2", "sig", null).write());
        com.dwinovo.numen.api.brain.CompanionBinding.skinChanged(server, UUID.randomUUID(), true, id.toString());

        helper.assertTrue("shared-v2".equals(registry.find(a.companion.getUUID()).skinValue()), "the first still wears the old one");
        helper.assertTrue("shared-v2".equals(registry.find(b.companion.getUUID()).skinValue()),
                "someone else's companion still wears the old one");
        for (Brain her : List.of(a, b)) {
            CompanionLoops.dismissed(server, her.companion.getUUID());
            NumenPlayer live = NumenPlayer.findByUuid(server, her.companion.getUUID());
            if (live != null) {
                CompanionFactory.despawn(server, live);
            }
            registry.remove(her.companion.getUUID());
        }
        shelf.delete(id);
        helper.succeed();
    }

    /** 每人最多几只是服主在 server.json 里设的;再召唤自己已有的同名同伴是叫醒她,不占名额。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void the_summon_limit_comes_from_the_server_settings(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_limited");
        var settings = com.dwinovo.numen.api.brain.ModelServices.of(server).settings();
        settings.setMaxCompanions(1);
        try {
            helper.assertTrue(com.dwinovo.numen.api.gametest.Bodies.summonFor(owner, "gametest_lim_a", false)
                    .problem() == null, "the first one was refused");
            var second = com.dwinovo.numen.api.gametest.Bodies.summonFor(owner, "gametest_lim_b", false);
            helper.assertTrue(second.problem() != null && second.body() == null, "the limit did not hold");
            helper.assertTrue(com.dwinovo.numen.api.gametest.Bodies.summonFor(owner, "gametest_lim_a", false)
                    .problem() == null, "waking the one she has took a slot");
            helper.assertTrue(!com.dwinovo.numen.api.gametest.Bodies.summonFor(owner, "x", false).problem()
                    .getString().isEmpty(), "a name that is not a name was summoned");
        } finally {
            settings.setMaxCompanions(0);
            for (var entry : CompanionRegistry.get(server).ownedBy(owner.getUUID())) {
                com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(entry.getKey()));
            }
            GameTestKit.leave(owner);
        }
        helper.succeed();
    }

    /** 人设改名(文件名即身份):在用它的同伴改指新名字。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void renaming_a_persona_repoints_the_companions_using_it(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_renamed");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();
        var library = com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner);
        var old = library.create("gametest_old_name", "文本");
        her.loop.bind(com.dwinovo.numen.api.brain.Slot.PERSONA, old.id());

        JsonObject body = new JsonObject();
        body.addProperty("id", old.id());
        body.addProperty("name", "gametest_new_name");
        body.addProperty("text", "文本");
        var result = com.dwinovo.numen.api.brain.Libraries.of(server).handle(owner, false,
                com.dwinovo.numen.api.brain.Libraries.PERSONA, com.dwinovo.numen.api.brain.Libraries.PUT, body);

        helper.assertTrue(result.problem() == null, "the rename was refused: " + result.problem());
        helper.assertTrue("my:gametest_new_name".equals(her.loop.bound(com.dwinovo.numen.api.brain.Slot.PERSONA)),
                "she still points at " + her.loop.bound(com.dwinovo.numen.api.brain.Slot.PERSONA));
        library.remove("my:gametest_new_name");
        her.close();
        helper.succeed();
    }

    /** 她存在就有大脑:按需建起来,再要一次是同一个。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_brain_is_made_on_demand_and_asking_again_gives_the_same_one(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        BlockPos at = helper.absolutePos(new BlockPos(3, 2, 3));
        NumenPlayer body = com.dwinovo.numen.api.gametest.Bodies.spawn(server, UUID.randomUUID(),
                "gametest_ondemand", UUID.randomUUID(), helper.getLevel(),
                new net.minecraft.world.phys.Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        CompanionRegistry.get(server).put(body.getUUID(), new CompanionRegistry.Entry("gametest_ondemand",
                body.getOwnerUuid(), Level.OVERWORLD, body.blockPosition()));
        Io io = NumenIo.inline("numen-gametest");
        Path notes = Files.createTempDirectory("numen-gametest-notes-");
        NoteBook.init(uuid -> new Home(notes, io), () -> 0);

        CompanionLoop loop = com.dwinovo.numen.api.brain.CompanionBrains.ensure(server, body.getUUID());

        helper.assertTrue(loop != null, "no brain was made");
        helper.assertTrue(com.dwinovo.numen.api.brain.CompanionBrains.ensure(server, body.getUUID()) == loop,
                "asking again made another brain");
        CompanionLoops.dismissed(server, body.getUUID());
        CompanionRegistry.get(server).remove(body.getUUID());
        CompanionFactory.despawn(server, body);
        helper.succeed();
    }

    /**
     * 管理员经协议存了内置的模型档案、再存服主的 key:档案里没有 key,key 在钥匙串里,只有他读得到;在线的别人收到新档案的表,
     * 表里没有 key。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void an_admin_adds_a_model_profile_and_everyone_online_hears_of_it_without_the_key(
            GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_boss");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        var other = GameTestKit.presentPlayer(helper, null, "gametest_hand");
        helper.assertTrue(com.dwinovo.numen.api.config.Authority.isAdmin(admin), "the test admin is not an admin");
        com.dwinovo.numen.api.brain.Replies.hello(other, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(admin);
        GameTestKit.received(other);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "put",
                "{\"name\":\"gametest_admin_profile\",\"group\":\"builtin\",\"provider\":\"openai\"}");

        var told = GameTestKit.received(admin);
        var heard = GameTestKit.received(other);
        helper.assertTrue(told.size() == 1 && told.get(0) instanceof com.dwinovo.numen.api.network.payload.LibraryPayload a
                        && a.problem().isEmpty() && a.entries().contains("server:gametest_admin_profile"),
                "the admin was not given the table with the new profile: " + told);
        helper.assertTrue(heard.size() == 1 && heard.get(0) instanceof com.dwinovo.numen.api.network.payload.LibraryPayload h
                        && h.entries().contains("gametest_admin_profile"),
                "the other player was not told of the new profile: " + heard);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.KEY, "put",
                "{\"service\":\"openai\",\"key\":\"sk-admin\",\"scope\":\"server\"}");
        com.dwinovo.numen.api.brain.Replies.library(other, com.dwinovo.numen.api.brain.Libraries.KEY, "list", "{}");
        var keys = GameTestKit.received(admin);
        var othersKeys = GameTestKit.received(other);
        helper.assertTrue(keys.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload k
                        && k.entries().contains("sk-admin")), "the admin cannot read the server's key: " + keys);
        helper.assertTrue(othersKeys.stream().noneMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload k
                        && k.entries().contains("sk-admin")), "the server's key reached another player: " + othersKeys);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.KEY, "put",
                "{\"service\":\"openai\",\"key\":\"\",\"scope\":\"server\"}");
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "delete",
                "{\"id\":\"server:gametest_admin_profile\",\"group\":\"builtin\"}");
        server.getPlayerList().deop(admin.getGameProfile());
        GameTestKit.leave(other);
        GameTestKit.leave(admin);
        helper.succeed();
    }

    /** 玩家经协议关掉一个技能、再打开它:两次都办成,他收到的整表里那一条先是关、后是开;开关是他自己的。 */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void a_player_switches_a_skill_off_and_on_again_through_the_protocol(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_skill_player");
        String skill = com.dwinovo.numen.api.library.LibraryHub.of(server)
                .builtin(com.dwinovo.numen.api.agent.skill.SkillRegistry.KIND).items().iterator().next().id().toString();
        String body = "{\"id\":\"" + skill + "\"}";
        GameTestKit.received(admin);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.SKILL, "disable", body);
        helper.assertTrue(skillEnabled(GameTestKit.received(admin), skill) == Boolean.FALSE,
                "the skill was not switched off");
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.SKILL, "enable", body);
        helper.assertTrue(skillEnabled(GameTestKit.received(admin), skill) == Boolean.TRUE,
                "the skill was not switched on again");

        GameTestKit.leave(admin);
        helper.succeed();
    }

    /** 管理员收到的那张技能表里这一条的开关;没收到表或表里没有这条是 {@code null}。 */
    private static Boolean skillEnabled(java.util.List<?> told, String skill) {
        for (Object packet : told) {
            if (packet instanceof com.dwinovo.numen.api.network.payload.LibraryPayload table
                    && com.dwinovo.numen.api.brain.Libraries.SKILL.equals(table.kind())) {
                for (var row : com.google.gson.JsonParser.parseString(table.entries()).getAsJsonArray()) {
                    var o = row.getAsJsonObject();
                    if (skill.equals(o.get("id").getAsString()) && "builtin".equals(o.get("group").getAsString())) {
                        return o.get("enabled").getAsBoolean();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 管理员的档案表单问目录与检测:目录、草稿解析出的端点、"缺 key"的判断都在服务端,答案经他自己的连接送回;
     * 检测先答"在试",结局到了再送一张;别的在线玩家什么也收不到。
     */
    @GameTest(template = "floor16", timeoutTicks = 100000, batch = "numen_brain")
    public static void the_profile_form_asks_the_server_for_the_catalog_and_the_connection_test(
            GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_form_boss");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        var other = GameTestKit.presentPlayer(helper, null, "gametest_form_hand");
        GameTestKit.received(admin);
        GameTestKit.received(other);

        String draft = "{\"draft\":{\"provider\":\"deepseek\"},\"models\":true}";
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.CATALOG, "list", draft);
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER_CHECK, "run",
                draft);

        List<com.dwinovo.numen.api.network.payload.LibraryPayload> told = new ArrayList<>();
        GameTestKit.steps(helper)
                .thenWaitUntil(() -> {
                    GameTestKit.received(admin).forEach(p -> {
                        if (p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l) {
                            told.add(l);
                        }
                    });
                    helper.assertTrue(told.stream().anyMatch(l -> l.entries().contains("need_key")),
                            "the connection test has not come back: " + told);
                })
                .thenExecute(() -> {
                    var catalog = told.stream().filter(l -> l.kind().equals(com.dwinovo.numen.api.brain.Libraries.CATALOG))
                            .findFirst();
                    helper.assertTrue(catalog.isPresent() && catalog.get().problem().isEmpty()
                                    && catalog.get().entries().contains("\"vendors\"")
                                    && catalog.get().entries().contains("\"preview\"")
                                    && catalog.get().entries().contains("\"models\""),
                            "the catalog was not answered whole: " + catalog);
                    helper.assertTrue(told.stream().anyMatch(l -> l.entries().contains("checking")),
                            "the test did not first say it was trying: " + told);
                    helper.assertTrue(GameTestKit.received(other).isEmpty(),
                            "the answer reached a player who did not ask");
                    server.getPlayerList().deop(admin.getGameProfile());
                    GameTestKit.leave(other);
                    GameTestKit.leave(admin);
                })
                .thenSucceed();
    }
}
