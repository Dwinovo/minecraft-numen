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
import com.dwinovo.numen.api.program.LocalPrograms;
import com.dwinovo.numen.api.store.Io;
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
        private final net.minecraft.server.MinecraftServer server;

        Brain(GameTestHelper helper, String name) throws java.io.IOException {
            this(helper, name, Files.createTempDirectory("numen-gametest-brain-"));
        }

        /** {@code dir} 里 {@code her/} 是她的家(可以已经有搬过来的东西)。 */
        Brain(GameTestHelper helper, String name, Path dir) throws java.io.IOException {
            this.server = helper.getLevel().getServer();
            this.companion = spawnAt(helper, name, new BlockPos(3, 2, 3), false);
            CompanionRegistry.get(server).put(companion.getUUID(), new CompanionRegistry.Entry(name,
                    companion.getOwnerUuid(), Level.OVERWORLD, companion.blockPosition()));
            Io io = NumenIo.inline("numen-gametest");
            NoteBook.init(uuid -> new Home(dir.resolve("memory"), io), () -> 0);
            this.rooms = Conversations.of(server, companion.getOwnerUuid());
            this.loop = new CompanionLoop(new CompanionLoop.Parts(companion.getUUID(), new Home(dir.resolve("her"), io),
                    server::execute, mind, new LocalPrograms(server), () -> companion, () -> null, () -> false,
                    () -> ownerOnline, () -> false, () -> 3, id -> null, () -> helper.getLevel().getDayTime(),
                    rooms));
            CompanionLoops.install(companion.getOwnerUuid(), loop, owner, rooms);
        }

        void close() {
            CompanionLoops.dismissed(server, companion.getUUID());
            CompanionRegistry.get(server).remove(companion.getUUID());
            CompanionFactory.despawn(server, companion);
        }

        List<ConvoState.Msg> transcript() {
            return loop.log().loadLines(100).stream().map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::msg).toList();
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
            List<ConvoState.Msg> t = her.transcript();
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
            helper.assertTrue(her.owner.events.stream().filter(e -> e instanceof Feed.Line).count() == 4,
                    "the owner's client did not get the four log lines: " + her.owner.events);
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
            helper.assertTrue(her.mind.asked.size() == 2 && her.transcript().size() == 4, "the loop has not finished");
            var conv = her.rooms.find(her.companion.getUUID().toString());
            History.Page newest = History.page(her.rooms, conv, null, 3).join();
            helper.assertTrue(newest.entries().size() == 3 && newest.more(), "newest page: " + newest);
            History.Page oldest = History.page(her.rooms, conv, newest.cursor(), 3).join();
            helper.assertTrue(oldest.entries().size() == 1 && !oldest.more(), "oldest page: " + oldest);
            List<String> ids = new ArrayList<>();
            oldest.entries().forEach(e -> ids.add(e.ids().get(e.companion())));
            newest.entries().forEach(e -> ids.add(e.ids().get(e.companion())));
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
}
