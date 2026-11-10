package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
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
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.mcp.server.McpConfig;
import com.dwinovo.numen.api.mcp.server.McpMode;
import com.dwinovo.numen.mcp.server.McpToken;

import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.GameTest;
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

    // 每个批次都自己定下世界(难度、时刻、天气、模拟距离、权限设置),不继承上一批留下的:批次的先后说不准
    @BeforeBatch(batch = "numen_brain")
    public static void prepareBrainBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

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

    /** 札记的落点已经换成测试的了。 */
    private static boolean testNotes;

    /**
     * 札记换到临时目录里,每只同伴一个子目录;整次开服只换一次。札记的落点是全服一份({@link NoteBook#init} 还会清掉开着的本子),
     * 每个用例各换一次,同一批里先起的同伴就会读写到后起的那一处去。
     */
    static void useTestNotes() throws java.io.IOException {
        if (testNotes) {
            return;
        }
        Path root = Files.createTempDirectory("numen-gametest-notes-");
        Io io = NumenIo.inline("numen-gametest");
        NoteBook.init(uuid -> new Home(root.resolve(uuid.toString()), io), () -> 0);
        testNotes = true;
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
            useTestNotes();
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void owner_words_run_the_whole_loop_on_the_server(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_thinker");
        her.mind.program("return 1 + 1").say("算好了:2");

        her.loop.submitPrompt("算一下 1 + 1");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, her.mind.asked.size() == 2, "the model was asked " + her.mind.asked.size() + " time(s)");
            List<ConvoState.Msg> t = her.conversation();
            Checks.assertTrue(helper, t.size() == 4 && t.get(0) instanceof ConvoState.Msg.User
                            && t.get(1) instanceof ConvoState.Msg.Assistant a && a.turn().hasToolCalls()
                            && t.get(2) instanceof ConvoState.Msg.Tool && t.get(3) instanceof ConvoState.Msg.Assistant,
                    "the log does not hold user, call, receipt, reply: " + t);
            ConvoState.Msg.Tool receipt = (ConvoState.Msg.Tool) t.get(2);
            Checks.assertTrue(helper, receipt.content().contains("2"), "the receipt does not carry the program's value: "
                    + receipt.content());
            Checks.assertTrue(helper, her.mind.asked.get(1).messages().stream().anyMatch(m -> m instanceof ConvoState.Msg.Tool),
                    "the second request did not carry the receipt");
            Checks.assertTrue(helper, Files.isRegularFile(her.loop.log().file()), "nothing was written to her home");
            Checks.assertTrue(helper, her.owner.events.stream().anyMatch(e -> e instanceof Feed.Replied r
                            && r.content().equals("算好了:2")), "the owner's client was not told her reply: " + her.owner.events);
            Checks.assertTrue(helper, her.owner.events.stream().filter(e -> e instanceof Feed.Line).count()
                            == her.loop.log().loadLines(100).size(),
                    "the owner's client did not get every log line: " + her.owner.events);
            Checks.assertTrue(helper, her.owner.events.get(her.owner.events.size() - 1) instanceof Feed.Status s
                            && s.state().phase() == null && s.state().hold() == null,
                    "the last thing the client hears is not that she is idle: " + her.owner.events);
            her.close();
        });
    }

    /**
     * 主人清空她的记忆:对话日志与札记删掉,名册与身体不动,面板收到"清空了",并且如实告诉她;导出的 zip 在清空之前带着
     * 日志和札记。只有主人能做;她正在想事情的时候不清,什么也不动。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
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
        Checks.assertTrue(helper, busy.problem() != null, "she was wiped in the middle of a thought");
        Checks.assertTrue(helper, !com.dwinovo.numen.api.agent.memory.NoteBook.of(her.companion.getUUID()).index().isEmpty(),
                "a refused wipe deleted her notes");
        var stranger = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, UUID.randomUUID(),
                new com.dwinovo.numen.api.brain.OwnerInput.Wipe(her.companion.getUUID()));
        Checks.assertTrue(helper, stranger.problem() != null, "someone else wiped her");
        her.mind.holding = false;
        her.mind.release();

        GameTestKit.steps(helper).thenWaitUntil(() -> Checks.assertTrue(helper, 
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
            Checks.assertTrue(helper, names.contains("chat.jsonl"), "export: " + names);

            var done = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner,
                    new com.dwinovo.numen.api.brain.OwnerInput.Wipe(her.companion.getUUID()));
            Checks.assertTrue(helper, done.problem() == null, "the wipe was refused: " + done.problem());
            Checks.assertTrue(helper, her.conversation().stream().noneMatch(m -> m instanceof ConvoState.Msg.User u
                    && u.content().contains("铁在东边")), "the old words are still in the log: " + her.conversation());
            Checks.assertTrue(helper, com.dwinovo.numen.api.agent.memory.NoteBook.of(her.companion.getUUID()).index().isEmpty(),
                    "her notes are still there");
            Checks.assertTrue(helper, CompanionRegistry.get(server).find(her.companion.getUUID()) != null,
                    "wiping her memory removed her from the roster");
            Checks.assertTrue(helper, her.loop.inbox().entries().stream().anyMatch(e -> e.text().contains("清空了你的记忆")),
                    "she was not told: " + her.loop.inbox().entries());
        }).thenWaitUntil(() -> Checks.assertTrue(helper, her.owner.events.stream().anyMatch(e -> e instanceof Feed.Wiped),
                "the panel was not told")).thenExecute(her::close).thenSucceed();
    }

    /**
     * 主人经札记库新增、修改、删除她的一条札记:她的索引(她的工具读写的那一本)跟着变,收件箱里每次都有一条知会;
     * 不是她主人的人动不了。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void the_owner_adds_edits_and_deletes_her_notes_and_she_is_told_each_time(GameTestHelper helper)
            throws Exception {
        Brain her = new Brain(helper, "gametest_noted");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();
        var libraries = com.dwinovo.numen.api.brain.Libraries.of(server);
        var notes = com.dwinovo.numen.api.brain.Libraries.NOTES;
        NoteBook book = NoteBook.of(her.companion.getUUID());
        int revision = book.revision();

        JsonObject add = new JsonObject();
        add.addProperty("companion", her.companion.getUUID().toString());
        add.addProperty("description", "铁在东边");
        var added = libraries.handle(owner, false, notes, com.dwinovo.numen.api.brain.Libraries.PUT, add);
        Checks.assertTrue(helper, added.problem() == null, "the owner's note was refused: " + added.problem());
        Checks.assertTrue(helper, book.index().size() == 1 && book.index().get(0).name().equals("note-1"),
                "the note was not filed under a name of its own: " + book.index());
        Checks.assertTrue(helper, book.revision() > revision && book.formatXml().contains("铁在东边"),
                "her index did not change: " + book.formatXml());
        Checks.assertTrue(helper, her.loop.inbox().entries().stream().anyMatch(e -> e.text().contains("note-1")
                && e.text().contains("加了一条")), "she was not told of the new note: " + her.loop.inbox().entries());

        JsonObject edit = add.deepCopy();
        edit.addProperty("name", "note-1");
        edit.addProperty("description", "铁在西边");
        Checks.assertTrue(helper, libraries.handle(owner, false, notes, com.dwinovo.numen.api.brain.Libraries.PUT, edit)
                .problem() == null, "the edit was refused");
        Checks.assertTrue(helper, book.formatXml().contains("铁在西边") && !book.formatXml().contains("铁在东边"),
                "her index still has the old line: " + book.formatXml());
        Checks.assertTrue(helper, her.loop.inbox().entries().stream().anyMatch(e -> e.text().contains("改了你的札记")),
                "she was not told of the edit: " + her.loop.inbox().entries());

        JsonObject stranger = edit.deepCopy();
        stranger.addProperty("description", "不是我写的");
        Checks.assertTrue(helper, libraries.handle(UUID.randomUUID(), false, notes,
                com.dwinovo.numen.api.brain.Libraries.PUT, stranger).problem() != null, "someone else wrote her note");
        Checks.assertTrue(helper, libraries.handle(UUID.randomUUID(), false, notes,
                com.dwinovo.numen.api.brain.Libraries.DELETE, stranger).problem() != null, "someone else deleted it");
        Checks.assertTrue(helper, book.read("note-1").description().equals("铁在西边"), "a stranger changed her note");

        JsonObject drop = new JsonObject();
        drop.addProperty("companion", her.companion.getUUID().toString());
        drop.addProperty("name", "note-1");
        Checks.assertTrue(helper, libraries.handle(owner, false, notes, com.dwinovo.numen.api.brain.Libraries.DELETE, drop)
                .problem() == null, "the owner's delete was refused");
        Checks.assertTrue(helper, book.index().isEmpty(), "the note is still there: " + book.index());
        Checks.assertTrue(helper, her.loop.inbox().entries().stream().anyMatch(e -> e.text().contains("删掉了你的札记")),
                "she was not told of the delete: " + her.loop.inbox().entries());
        her.close();
        helper.succeed();
    }

    /** 一条定时的最短间隔。 */
    private static final int ONE_INTERVAL = (int) com.dwinovo.numen.api.brain.OwnerSchedules.MIN_EVERY_TICKS;

    /**
     * 定时只有她的主人能增删改(别人被拒),服务端判条数与间隔,答复是这位玩家看得到的整表。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void only_the_owner_adds_edits_and_deletes_her_schedules(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_schedule_owner");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();
        var libraries = com.dwinovo.numen.api.brain.Libraries.of(server);
        String kind = com.dwinovo.numen.api.brain.Libraries.SCHEDULES;
        String put = com.dwinovo.numen.api.brain.Libraries.PUT;

        JsonObject add = new JsonObject();
        add.addProperty("companion", her.companion.getUUID().toString());
        add.addProperty("text", "看看炉子");
        add.addProperty("every", ONE_INTERVAL);
        var added = libraries.handle(owner, false, kind, put, add);
        Checks.assertTrue(helper, added.problem() == null && added.entries().contains("看看炉子"),
                "the owner's schedule was refused or not listed: " + added.problem() + " " + added.entries());
        String id = her.loop.schedules().get(0).id();

        JsonObject edit = add.deepCopy();
        edit.addProperty("id", id);
        edit.addProperty("text", "看看箱子");
        Checks.assertTrue(helper, libraries.handle(owner, false, kind, put, edit).problem() == null
                && her.loop.schedules().size() == 1 && her.loop.schedules().get(0).text().equals("看看箱子"),
                "the edit did not replace the schedule: " + her.loop.schedules());

        JsonObject tooOften = add.deepCopy();
        tooOften.addProperty("every", ONE_INTERVAL - 1);
        Checks.assertTrue(helper, libraries.handle(owner, false, kind, put, tooOften).problem() != null,
                "a schedule shorter than the minimum interval was accepted");

        JsonObject stranger = edit.deepCopy();
        stranger.addProperty("text", "不是我定的");
        Checks.assertTrue(helper, libraries.handle(UUID.randomUUID(), false, kind, put, stranger).problem() != null,
                "someone else changed her schedule");
        Checks.assertTrue(helper, libraries.handle(UUID.randomUUID(), false, kind,
                com.dwinovo.numen.api.brain.Libraries.DELETE, stranger).problem() != null, "someone else deleted it");
        Checks.assertTrue(helper, her.loop.schedules().get(0).text().equals("看看箱子"), "a stranger changed her schedule");

        JsonObject drop = new JsonObject();
        drop.addProperty("companion", her.companion.getUUID().toString());
        drop.addProperty("id", id);
        Checks.assertTrue(helper, libraries.handle(owner, false, kind, com.dwinovo.numen.api.brain.Libraries.DELETE, drop)
                .problem() == null && her.loop.schedules().isEmpty(), "the owner's delete did not remove it");
        her.close();
        helper.succeed();
    }

    /** 历史按游标一页一页往前翻,翻完正好是整份记录,一行不重不漏。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void history_pages_walk_back_by_cursor(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_pager");
        her.mind.program("return 1 + 1").say("算好了:2");
        her.loop.submitPrompt("算一下 1 + 1");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, her.mind.asked.size() == 2 && her.conversation().size() == 4, "the loop has not finished");
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
            Checks.assertTrue(helper, pages >= 2, "the record fit on one page of three: " + ids);
            Checks.assertTrue(helper, ids.equals(her.loop.log().loadLines(100).stream()
                            .map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::id).toList()),
                    "the pages do not add up to the whole record: " + ids);
            her.close();
        });
    }

    /** 主人经客户端说话:过归属检查,进她的收件箱;别人名下的同伴、不存在的会话一概不收。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void an_owners_words_reach_only_his_own_companion(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_mine");
        her.mind.say("在的");
        var server = helper.getLevel().getServer();
        UUID stranger = UUID.randomUUID();

        Checks.assertTrue(helper, OwnerInputs.apply(server, stranger, new OwnerInput.Say(her.companion.getUUID().toString(),
                "我不是你主人")).problem() != null, "a stranger's words were accepted");
        Checks.assertTrue(helper, OwnerInputs.apply(server, stranger, new OwnerInput.Compact(her.companion.getUUID(), null))
                .problem() != null, "a stranger's command was accepted");
        Checks.assertTrue(helper, her.mind.asked.isEmpty(), "she thought about a stranger's words");

        var applied = OwnerInputs.apply(server, her.companion.getOwnerUuid(),
                new OwnerInput.Say(her.companion.getUUID().toString(), "你在吗"));
        Checks.assertTrue(helper, applied.problem() == null, "the owner's words were refused: " + applied.problem());

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, her.mind.asked.size() == 1, "the owner's words did not wake her");
            her.close();
        });
    }

    /** 旧版本留在客户端的对话、收件箱、目标搬进世界之后,她醒来还记得。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
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

        Checks.assertTrue(helper, com.dwinovo.numen.api.brain.LegacyMigration.companion(legacy, dir.resolve("her")),
                "nothing was migrated");
        Brain her = new Brain(helper, "gametest_rememberer", dir);

        Checks.assertTrue(helper, her.transcript().size() == 2 && her.transcript().get(0) instanceof ConvoState.Msg.User,
                "the migrated conversation is not there: " + her.transcript());
        Checks.assertTrue(helper, her.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TASK_FINISHED)),
                "the migrated inbox is not there");
        Checks.assertTrue(helper, her.loop.goal() != null && her.loop.goal().objective().equals("挖 64 个铁"),
                "the migrated goal is not there");
        her.close();
        helper.succeed();
    }

    /**
     * 装着客户端的玩家进服,什么也不用调:平台的登入响应自己把开场那包(协议号)发出去,客户端才有得报到。登入响应赶不上"玩家已在玩家表里"那一刻时
     * (Fabric 的登入事件发在原版登记玩家之前),开场就发不出去,她的程序里的客户端函数、她的大脑都找不到这位主人的客户端。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_player_joining_with_our_client_is_opened_to_by_the_platform(GameTestHelper helper) {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_opened");
        var sent = GameTestKit.received(owner).stream()
                .filter(p -> p instanceof com.dwinovo.numen.api.network.payload.ServerHelloPayload opener
                        && opener.protocol() == com.dwinovo.numen.api.brain.Presence.PROTOCOL)
                .toList();
        Checks.assertTrue(helper, sent.size() == 1, "the opener was not sent once when the player joined: " + sent);
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /**
     * 装了客户端的主人进服:报到之前服务端只发开场那一包(协议号),名册也等着;报到之后得到回答(是不是管理员)、名册,
     * 并补上名下大脑此刻的样子与会话列表,整条路经真的发包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_client_that_says_hello_is_answered_and_caught_up(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_greeter");
        Brain her = new Brain(helper, "gametest_greeted", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID());
        // 开场在平台的登入或频道登记那一刻发;再调一次不重发
        com.dwinovo.numen.api.platform.PlayerSessions.channelReady(owner);
        com.dwinovo.numen.api.entity.Companions.syncRosterToOwner(owner.level().getServer(), owner);

        var before = GameTestKit.received(owner).stream()
                .filter(p -> p.type().id().getNamespace().equals(com.dwinovo.numen.api.Constants.MOD_ID)).toList();
        Checks.assertTrue(helper, before.size() == 1
                        && before.get(0) instanceof com.dwinovo.numen.api.network.payload.ServerHelloPayload opener
                        && opener.protocol() == com.dwinovo.numen.api.brain.Presence.PROTOCOL,
                "before the hello the server sent more than its opener: " + before);

        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL);

        var sent = GameTestKit.received(owner);
        Checks.assertTrue(helper, sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.HelloAckPayload a
                        && !a.admin()),
                "no hello answer for an ordinary player: " + sent);
        Checks.assertTrue(helper, sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.CompanionListPayload),
                "the roster was not sent after the hello: " + sent);
        Checks.assertTrue(helper, sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.FeedPayload f
                        && f.companion().equals(her.companion.getUUID())
                        && f.events().stream().anyMatch(e -> e instanceof Feed.Status)),
                "her state was not caught up: " + sent);
        Checks.assertTrue(helper, sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.ConversationsPayload),
                "the conversation list was not sent");
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 协议版本对不上的客户端不被服务:开场那包已经告诉过它服务端的版本,之后什么也不发——回答、名册、她的东西都不发。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_client_of_another_protocol_is_sent_nothing_more(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_mismatch");
        Brain her = new Brain(helper, "gametest_unserved", Files.createTempDirectory("numen-gametest-brain-"),
                owner.getUUID());
        GameTestKit.received(owner);

        com.dwinovo.numen.api.brain.Replies.hello(owner, com.dwinovo.numen.api.brain.Presence.PROTOCOL + 1);
        com.dwinovo.numen.api.entity.Companions.syncRosterToOwner(owner.level().getServer(), owner);

        var sent = GameTestKit.received(owner);
        Checks.assertTrue(helper, sent.isEmpty(), "an unserved client was sent something: " + sent);
        her.close();
        GameTestKit.leave(owner);
        helper.succeed();
    }

    private static int indexOf(List<ConvoState.Msg> messages, java.util.function.Predicate<ConvoState.Msg> match) {
        for (int i = 0; i < messages.size(); i++) {
            if (match.test(messages.get(i))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    /** 一个 tools/call 结果的第 {@code index} 段文本;负数从尾数起。 */
    private static String textAt(JsonObject result, int index) {
        com.google.gson.JsonArray content = result.getAsJsonArray("content");
        return content.get(index < 0 ? content.size() + index : index).getAsJsonObject().get("text").getAsString();
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
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void an_external_brain_drives_her_through_the_mcp_host(GameTestHelper helper) throws Exception {
        String name = "gametest_driven";
        Brain her = new Brain(helper, name);
        her.driving = true;
        List<String> pluginHeard = new java.util.concurrent.CopyOnWriteArrayList<>();
        String pluginId = "gt_driven_hears";
        hearRuns(pluginId, her.companion.getUUID(), pluginHeard);
        int port;
        try (var probe = new java.net.ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        McpMode mcp = McpMode.instance();
        Checks.assertTrue(helper, mcp.applySettings(McpConfig.LOOPBACK, port, 30, List.of()) && mcp.setEnabled(true),
                "the endpoint did not start: " + mcp.lastError());
        McpTestClient client = new McpTestClient(port, mcp.tokens().issue(her.companion.getOwnerUuid(),
                McpToken.Role.BRAIN, McpToken.Scope.ALL, "gametest", 0).secret());
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> states = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean done = new java.util.concurrent.atomic.AtomicBoolean();
        McpTestClient.drive("gametest-external-brain", () -> {
            try {
                client.connect();
                heard.add(client.call("list_companions", new JsonObject()));
                JsonObject wait = mcpArgs(name, null, null);
                wait.addProperty("wait_seconds", 20);
                JsonObject events = client.callResult("get_events", wait);
                heard.add(textAt(events, 0));
                states.add(textAt(events, -1));
                heard.add(client.call("say", mcpArgs(name, "text", "收到")));
                JsonObject program = ScriptTool.args("return 1 + 1");
                program.addProperty("companion", name);
                JsonObject receipt = client.callResult(ScriptEngine.IN_USE.toolName(), program);
                heard.add(textAt(receipt, 0));
                states.add(textAt(receipt, -1));
            } finally {
                done.set(true);
            }
        }, failure::set);
        helper.runAfterDelay(10, () -> her.loop.submitPrompt("你好"));

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, done.get(), "the external brain has not finished: " + heard);
            mcp.setEnabled(false);
            Checks.assertTrue(helper, failure.get() == null, "the external brain failed: " + failure.get());
            Checks.assertTrue(helper, heard.get(0).contains(name), "list_companions did not name her: " + heard.get(0));
            Checks.assertTrue(helper, heard.get(1).contains("你好"), "get_events did not hand over the owner's words: " + heard.get(1));
            Checks.assertTrue(helper, heard.get(2).equals("said"), "say was not accepted: " + heard.get(2));
            Checks.assertTrue(helper, heard.get(3).contains("2"), "the program's receipt lacks its value: " + heard.get(3));
            Checks.assertTrue(helper, states.size() == 2 && states.stream().allMatch(st -> st.startsWith("<runtime_state>")
                            && st.contains("<inventory>")),
                    "get_events and the program's receipt do not end with her runtime state and inventory: " + states);
            List<ConvoState.Msg> t = her.transcript();
            Checks.assertTrue(helper, t.get(0) instanceof ConvoState.Msg.User u && u.content().contains("你好"),
                    "the owner's words taken by the external brain are not in the log: " + t);
            int said = indexOf(t, m -> m instanceof ConvoState.Msg.Assistant a && a.turn().content().equals("收到"));
            int call = indexOf(t, m -> m instanceof ConvoState.Msg.Assistant a && a.turn().hasToolCalls());
            Checks.assertTrue(helper, 0 < said && said < call && call < t.size() - 1 && t.get(call + 1) instanceof ConvoState.Msg.Tool,
                    "the log does not hold what she said, then the call and its receipt: " + t);
            Checks.assertTrue(helper, her.owner.events.stream().anyMatch(e -> e instanceof Feed.Replied r
                    && r.content().equals("收到")), "the owner's client was not told what she said");
            Checks.assertTrue(helper, her.mind.asked.isEmpty(), "her own brain thought while the external one drove");
            Checks.assertTrue(helper, pluginHeard.isEmpty(), "a run event went out while the external brain drove: " + pluginHeard);
            com.dwinovo.numen.api.NumenPlugins.roster().withdraw(pluginId);
            her.close();
        });
    }

    /** 一个听她的 run 开收的插件:把她的每一次开收记成一行;只记这只同伴的(别的用例的同伴也在发)。 */
    private static void hearRuns(String pluginId, UUID companion, List<String> heard) {
        com.dwinovo.numen.api.NumenPlugins.register(pluginId, numen -> {
            numen.on(com.dwinovo.numen.api.CompanionEvent.RUN_STARTED, started -> {
                if (started.companion().equals(companion)) {
                    heard.add("started " + started.runId());
                }
            });
            numen.on(com.dwinovo.numen.api.CompanionEvent.RUN_ENDED, ended -> {
                if (ended.companion().equals(companion)) {
                    heard.add("ended " + ended.runId() + " " + ended.outcome() + " " + ended.reason());
                }
            });
        });
    }

    /**
     * 插件听得到她的 run:一次正常的 run 先开后收、结局是完成;一次被主人停止切断的 run 结局是被切断、原因是主人停止。
     * 一次 run 一对,按发生的先后。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_plugin_hears_her_runs_start_and_end_in_order(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_run_heard");
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        String pluginId = "gt_run_hears";
        hearRuns(pluginId, her.companion.getUUID(), heard);
        her.mind.say("好的");

        her.loop.submitPrompt("你好");

        helper.runAfterDelay(20, () -> {
            Checks.assertTrue(helper, heard.size() == 2 && heard.get(0).startsWith("started ")
                            && heard.get(1).endsWith(" DONE ") && heard.get(1).startsWith("ended "
                            + heard.get(0).substring("started ".length())),
                    "a finished run was not heard as one start then one end: " + heard);
            heard.clear();
            her.mind.holding = true;
            her.mind.say("这句不该被听到");
            her.loop.submitPrompt("去挖矿");
        });
        helper.runAfterDelay(30, () -> {
            Checks.assertTrue(helper, heard.size() == 1 && heard.get(0).startsWith("started "), "the second run did not start: " + heard);
            her.loop.abort();
        });
        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, heard.size() == 2 && heard.get(1).endsWith(" HALTED halt.owner_stop"),
                    "the cut run was not heard as halted by the owner: " + heard);
            com.dwinovo.numen.api.NumenPlugins.roster().withdraw(pluginId);
            her.close();
        });
    }

    /**
     * 外脑的一段程序跑到一半她死了:那一次调用恰好收到一条失败的回答(不是挂着不回),她重生之后外脑再发的调用照常收下、照常跑完——
     * 不是永远被"已有调用在跑"挡在外面(#152)。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_brain")
    public static void a_program_cut_by_her_death_answers_once_and_the_next_call_runs(GameTestHelper helper)
            throws Exception {
        Brain her = new Brain(helper, "gametest_cut_driven");
        her.driving = true;
        UUID uuid = her.companion.getUUID();
        List<String> cutAnswers = new java.util.concurrent.CopyOnWriteArrayList<>();
        List<String> nextAnswers = new java.util.concurrent.CopyOnWriteArrayList<>();
        var cut = new com.dwinovo.numen.api.agent.provider.LlmToolCall("ext-gt-1", ScriptEngine.IN_USE.toolName(),
                ScriptTool.args("numen.time.wait(60)").toString());
        Checks.assertTrue(helper, her.loop.runExternal(cut, p -> {}, cutAnswers::add), "the long program was not accepted");
        helper.runAfterDelay(20, () -> {
            Checks.assertTrue(helper, cutAnswers.isEmpty(), "the program ended before she died: " + cutAnswers);
            her.companion.kill(helper.getLevel());
        });

        boolean[] nextSent = {false};
        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, cutAnswers.size() == 1, "the cut call was answered " + cutAnswers.size() + " time(s): " + cutAnswers);
            NumenPlayer now = NumenPlayer.findByUuid(helper.getLevel().getServer(), uuid);
            Checks.assertTrue(helper, now != null && now.isAlive() && her.loop.status().hold() != Hold.DEAD, "she has not respawned");
            if (!nextSent[0]) {
                var next = new com.dwinovo.numen.api.agent.provider.LlmToolCall("ext-gt-2", ScriptEngine.IN_USE.toolName(),
                        ScriptTool.args("return 1 + 1").toString());
                Checks.assertTrue(helper, her.loop.runExternal(next, p -> {}, nextAnswers::add),
                        "the call after her death was refused as busy");
                nextSent[0] = true;
            }
            Checks.assertTrue(helper, nextAnswers.size() == 1 && nextAnswers.get(0).contains("2"),
                    "the call after her death did not run to its value: " + nextAnswers);
            Checks.assertTrue(helper, cutAnswers.size() == 1, "the cut call was answered again: " + cutAnswers);
            her.close();
        });
    }

    /** 群里 @ 谁谁醒,别人旁听:旁听是捎带的事件,不叫醒她。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
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
        Checks.assertTrue(helper, pulled.problem() == null && pulled.conversationsChanged(), "the group was not made: " + pulled);
        var said = OwnerInputs.apply(server, owner, new OwnerInput.Say("gametest-group", "@gametest_ga 过来一下"));
        Checks.assertTrue(helper, said.problem() == null, "the words were refused: " + said.problem());

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, a.mind.asked.size() == 1, "the one called did not wake");
            Checks.assertTrue(helper, b.mind.asked.isEmpty(), "the one who only overheard woke up");
            Checks.assertTrue(helper, b.loop.inbox().entries().stream().anyMatch(e -> e.type().equals(EventTypes.TALK)
                    && e.text().contains("过来一下")), "the other did not overhear: " + b.loop.inbox().entries());
            a.close();
            b.close();
        });
    }

    /** 渠道(桥接、弹幕)送来的输入和主人的话走同一个门:认归属、认类型。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_bridge_input_reaches_her_like_the_owners_words(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_bridged");
        her.mind.say("收到弹幕");
        var server = helper.getLevel().getServer();
        UUID owner = her.companion.getOwnerUuid();

        Checks.assertTrue(helper, OwnerInputs.apply(server, owner, new OwnerInput.Emit(her.companion.getUUID(), "no_such_type",
                "x")).problem() != null, "an unregistered type was accepted");
        Checks.assertTrue(helper, OwnerInputs.apply(server, UUID.randomUUID(), new OwnerInput.Emit(her.companion.getUUID(),
                EventTypes.QUERY, "x")).problem() != null, "a stranger's input was accepted");
        var applied = OwnerInputs.apply(server, owner, new OwnerInput.Emit(her.companion.getUUID(), EventTypes.QUERY,
                "弹幕:加油"));
        Checks.assertTrue(helper, applied.problem() == null, "the owner's bridge was refused: " + applied.problem());

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, her.mind.asked.size() == 1, "the bridged words did not wake her");
            Checks.assertTrue(helper, her.transcript().get(0) instanceof ConvoState.Msg.User u && u.content().contains("弹幕:加油"),
                    "the bridged words are not in her log: " + her.transcript());
            her.close();
        });
    }

    /** 外接大脑把驾驶席交还:驾驶期间攒下的话,内脑回来就接着答。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void handing_the_wheel_back_lets_her_answer_what_waited(GameTestHelper helper) throws Exception {
        Brain her = new Brain(helper, "gametest_handback");
        her.mind.say("我在");
        her.driving = true;
        her.loop.submitPrompt("在吗");

        helper.runAfterDelay(5, () -> {
            Checks.assertTrue(helper, her.mind.asked.isEmpty(), "she thought while an external brain drove");
            Checks.assertTrue(helper, her.loop.status().hold() == Hold.EXTERNAL, "not held: " + her.loop.status().hold());
            her.driving = false;
        });
        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, her.mind.asked.size() == 1, "she did not take the wheel back");
            Checks.assertTrue(helper, her.loop.status().hold() == null, "still held: " + her.loop.status().hold());
            her.close();
        });
    }

    /**
     * 召唤只要名字,不设前提:什么都没选的召唤,她有服务端大脑,可是还没绑档案,主人跟她说话时她停在 BLOCKED、原因是"还没配模型档案";
     * 档案、人设、声线之后经同一个入口绑上,三项都记进她的绑定。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void summoning_takes_only_a_name_and_the_three_bindings_come_afterwards(GameTestHelper helper)
            throws Exception {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_summoner");

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_born",
                        com.dwinovo.numen.api.entity.SummonSetup.NONE), owner);

        var born = com.dwinovo.numen.api.gametest.Bodies.awaitPlayer(server, "gametest_born");
        Checks.assertTrue(helper, born instanceof NumenPlayer, "the companion was not summoned");
        var brain = CompanionLoops.get(born.getUUID());
        Checks.assertTrue(helper, brain.isPresent(), "she has no server brain");
        for (var slot : com.dwinovo.numen.api.brain.Slot.values()) {
            Checks.assertTrue(helper, brain.get().bound(slot) == null, "she came with a " + slot + " already bound");
        }
        brain.get().submitPrompt("在吗");
        helper.runAfterDelay(20, () -> {
            Checks.assertTrue(helper, brain.get().status().hold() == Hold.BLOCKED, "not held: " + brain.get().status().hold());
            Checks.assertTrue(helper, com.dwinovo.numen.api.agent.loop.Cause.UNBOUND.equals(
                    brain.get().status().holdReason().code()), "wrong reason: " + brain.get().status().holdReason());

            var persona = com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID())
                    .create("gametest_persona", "你是测试员");
            var profiles = com.dwinovo.numen.api.brain.ModelServices.of(server).providers(owner.getUUID());
            var profile = profiles.create("gametest_profile",
                    new com.dwinovo.numen.api.agent.llm.ConnectionProfile("deepseek", null, "deepseek-chat", null,
                            "https://relay.example", null, null, 0, null, 0, null, 0, 0));
            var hub = com.dwinovo.numen.api.library.LibraryHub.of(server);
            var voiceId = com.dwinovo.numen.api.library.EntryId.mine("gametest_voice");
            hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().write(voiceId,
                    new com.dwinovo.numen.api.voice.VoiceProfile("", "openai", "", "", "m", "alex", "", "", "", 1f)
                            .write());

            var slots = com.dwinovo.numen.api.brain.Slot.values();
            String[] entries = {profile.id(), persona.id(), voiceId.toString()};
            for (int i = 0; i < slots.length; i++) {
                var applied = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner.getUUID(),
                        new com.dwinovo.numen.api.brain.OwnerInput.Bind(born.getUUID(), slots[i], entries[i]));
                Checks.assertTrue(helper, applied.problem() == null, slots[i] + " was refused: " + applied.problem());
                Checks.assertTrue(helper, entries[i].equals(brain.get().bound(slots[i])), slots[i] + " not bound");
            }
            var refused = com.dwinovo.numen.api.brain.OwnerInputs.apply(server, owner.getUUID(),
                    new com.dwinovo.numen.api.brain.OwnerInput.Bind(born.getUUID(),
                            com.dwinovo.numen.api.brain.Slot.VOICE, "my:nowhere"));
            Checks.assertTrue(helper, refused.problem() != null, "an entry that is not in the library was bound");
            Checks.assertTrue(helper, voiceId.toString().equals(brain.get().bound(com.dwinovo.numen.api.brain.Slot.VOICE)),
                    "a refused bind changed her voice");

            com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(born.getUUID()));
            profiles.remove(profile.id());
            com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID()).remove(persona.id());
            hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().delete(voiceId);
            GameTestKit.leave(owner);
            helper.succeed();
        });
    }

    /**
     * 召唤卡上改过的配置随召唤请求一起来:她到达时三项绑定与模式已经落下,和事后在编辑卡上绑走的是同一条路。
     * 主人在创造,创造的门是开的。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_brain")
    public static void a_summon_request_with_a_setup_has_her_bound_as_she_arrives(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_setup_owner");
        owner.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        var persona = com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID())
                .create("gametest_setup_persona", "你是测试员");
        var profiles = com.dwinovo.numen.api.brain.ModelServices.of(server).providers(owner.getUUID());
        var profile = profiles.create("gametest_setup_profile",
                new com.dwinovo.numen.api.agent.llm.ConnectionProfile("deepseek", null, "deepseek-chat", null,
                        "https://relay.example", null, null, 0, null, 0, null, 0, 0));
        var hub = com.dwinovo.numen.api.library.LibraryHub.of(server);
        var voiceId = com.dwinovo.numen.api.library.EntryId.mine("gametest_setup_voice");
        hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().write(voiceId,
                new com.dwinovo.numen.api.voice.VoiceProfile("", "openai", "", "", "m", "alex", "", "", "", 1f).write());
        var entries = new java.util.EnumMap<com.dwinovo.numen.api.brain.Slot, String>(
                com.dwinovo.numen.api.brain.Slot.class);
        entries.put(com.dwinovo.numen.api.brain.Slot.PROVIDER, profile.id());
        entries.put(com.dwinovo.numen.api.brain.Slot.PERSONA, persona.id());
        entries.put(com.dwinovo.numen.api.brain.Slot.VOICE, voiceId.toString());

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_setup",
                        new com.dwinovo.numen.api.entity.SummonSetup(entries, true, null)), owner);

        var born = com.dwinovo.numen.api.gametest.Bodies.awaitPlayer(server, "gametest_setup");
        helper.assertTrue(born instanceof NumenPlayer, "the companion was not summoned");
        var brain = CompanionLoops.get(born.getUUID());
        helper.assertTrue(brain.isPresent(), "she has no server brain");
        entries.forEach((slot, entry) ->
                helper.assertTrue(entry.equals(brain.get().bound(slot)), slot + " was not bound as she arrived"));
        helper.assertTrue(born.isCreative(), "she did not arrive in the mode picked on the card");

        com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(born.getUUID()));
        profiles.remove(profile.id());
        com.dwinovo.numen.api.brain.PersonaLibraries.of(server, owner.getUUID()).remove(persona.id());
        hub.library(com.dwinovo.numen.api.voice.VoiceProfile.KIND, owner.getUUID()).mine().delete(voiceId);
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 召唤请求带的配置有一项不过门(库里没有这一条):整次不召唤,告诉主人是哪一项。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_brain")
    public static void a_setup_item_that_fails_its_check_refuses_the_summon_and_says_why(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_refused_owner");
        GameTestKit.said(owner);

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_refused",
                        new com.dwinovo.numen.api.entity.SummonSetup(
                                java.util.Map.of(com.dwinovo.numen.api.brain.Slot.VOICE, "my:nowhere"), null, null)),
                owner);

        helper.assertTrue(server.getPlayerList().getPlayerByName("gametest_refused") == null, "she was summoned anyway");
        helper.assertTrue(CompanionRegistry.get(server).ownedBy(owner.getUUID()).isEmpty(), "a refused summon left an entry");
        var said = GameTestKit.said(owner);
        // 服务端按主人的语言翻好再发:看说没说是哪一只、哪一项
        helper.assertTrue(said.stream().map(s -> s.text().getString())
                        .anyMatch(text -> text.contains("gametest_refused") && text.contains("my:nowhere")),
                "the owner was not told which item failed: " + said);
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 人设改名(文件名即身份):在用它的同伴改指新名字。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
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

        Checks.assertTrue(helper, result.problem() == null, "the rename was refused: " + result.problem());
        Checks.assertTrue(helper, "my:gametest_new_name".equals(her.loop.bound(com.dwinovo.numen.api.brain.Slot.PERSONA)),
                "she still points at " + her.loop.bound(com.dwinovo.numen.api.brain.Slot.PERSONA));
        library.remove("my:gametest_new_name");
        her.close();
        helper.succeed();
    }

    /**
     * 管理员经协议存了内置的模型档案、再存服主的 key:档案里没有 key,key 在钥匙串里,只有他读得到;在线的别人收到新档案的表,
     * 表里没有 key。普通玩家经协议改不了服务器上的内置模型档案和配置,回话说明原因。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void an_admin_adds_a_model_profile_and_everyone_online_hears_of_it_without_the_key(
            GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_boss");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        var other = GameTestKit.presentPlayer(helper, null, "gametest_hand");
        Checks.assertTrue(helper, com.dwinovo.numen.api.config.Authority.isAdmin(admin), "the test admin is not an admin");
        com.dwinovo.numen.api.brain.Replies.hello(admin, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        com.dwinovo.numen.api.brain.Replies.hello(other, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(admin);
        GameTestKit.received(other);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "put",
                "{\"name\":\"gametest_admin_profile\",\"group\":\"server\",\"provider\":\"openai\"}");

        var told = GameTestKit.received(admin);
        var heard = GameTestKit.received(other);
        Checks.assertTrue(helper, told.size() == 1 && told.get(0) instanceof com.dwinovo.numen.api.network.payload.LibraryPayload a
                        && a.problem().isEmpty() && a.entries().contains("server:gametest_admin_profile"),
                "the admin was not given the table with the new profile: " + told);
        Checks.assertTrue(helper, heard.size() == 1 && heard.get(0) instanceof com.dwinovo.numen.api.network.payload.LibraryPayload h
                        && h.entries().contains("gametest_admin_profile"),
                "the other player was not told of the new profile: " + heard);

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.KEY, "put",
                "{\"service\":\"openai\",\"key\":\"sk-admin\",\"scope\":\"server\"}");
        com.dwinovo.numen.api.brain.Replies.library(other, com.dwinovo.numen.api.brain.Libraries.KEY, "list", "{}");
        var keys = GameTestKit.received(admin);
        var othersKeys = GameTestKit.received(other);
        Checks.assertTrue(helper, keys.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload k
                        && k.entries().contains("sk-admin")), "the admin cannot read the server's key: " + keys);
        Checks.assertTrue(helper, othersKeys.stream().noneMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload k
                        && k.entries().contains("sk-admin")), "the server's key reached another player: " + othersKeys);

        Checks.assertTrue(helper, !com.dwinovo.numen.api.config.Authority.isAdmin(other), "the other test player is an admin");
        var builtin = com.dwinovo.numen.api.library.LibraryHub.of(server)
                .server(com.dwinovo.numen.api.agent.llm.ProviderLibrary.KIND);
        int before = builtin.items().size();
        com.dwinovo.numen.api.brain.Replies.library(other, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "put",
                "{\"name\":\"偷偷加的\",\"group\":\"server\",\"provider\":\"openai\"}");
        com.dwinovo.numen.api.brain.Replies.library(other, com.dwinovo.numen.api.brain.Libraries.SERVER_CONFIG, "put",
                "{\"proxy\":\"evil:1\"}");
        var refused = GameTestKit.received(other);
        Checks.assertTrue(helper, refused.size() == 2 && refused.stream().allMatch(p ->
                        p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l && !l.problem().isEmpty()),
                "the refusals were not told: " + refused);
        Checks.assertTrue(helper, builtin.items().size() == before, "a built-in profile was added by an ordinary player");
        Checks.assertTrue(helper, !com.dwinovo.numen.api.platform.Services.config().getProxy().equals("evil:1"),
                "the proxy was changed by an ordinary player");

        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.KEY, "put",
                "{\"service\":\"openai\",\"key\":\"\",\"scope\":\"server\"}");
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER, "delete",
                "{\"id\":\"server:gametest_admin_profile\",\"group\":\"server\"}");
        server.getPlayerList().deop(admin.getGameProfile());
        GameTestKit.leave(other);
        GameTestKit.leave(admin);
        helper.succeed();
    }

    /**
     * 管理员的档案表单问目录与检测:目录、草稿解析出的端点、"缺 key"的判断都在服务端,答案经他自己的连接送回;
     * 检测先答"在试"(一张不答任何请求的表),结局到了再送一张,请求号跟着结局那一张回去,表单凭号认出这是它的答复;
     * 别的在线玩家什么也收不到。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void the_profile_form_asks_the_server_for_the_catalog_and_the_connection_test(
            GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var admin = GameTestKit.presentPlayer(helper, null, "gametest_form_boss");
        server.getPlayerList().getOps().add(new net.minecraft.server.players.ServerOpListEntry(admin.getGameProfile(),
                4, false));
        var other = GameTestKit.presentPlayer(helper, null, "gametest_form_hand");
        com.dwinovo.numen.api.brain.Replies.hello(admin, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        com.dwinovo.numen.api.brain.Replies.hello(other, com.dwinovo.numen.api.brain.Presence.PROTOCOL);
        GameTestKit.received(admin);
        GameTestKit.received(other);

        String draft = "{\"draft\":{\"provider\":\"deepseek\"},\"models\":true}";
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.CATALOG, "list", draft);
        com.dwinovo.numen.api.brain.Replies.library(admin, com.dwinovo.numen.api.brain.Libraries.PROVIDER_CHECK, "run",
                draft, 9);

        List<com.dwinovo.numen.api.network.payload.LibraryPayload> told = new ArrayList<>();
        GameTestKit.steps(helper)
                .thenWaitUntil(() -> {
                    GameTestKit.received(admin).forEach(p -> {
                        if (p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l) {
                            told.add(l);
                        }
                    });
                    Checks.assertTrue(helper, told.stream().anyMatch(l -> l.entries().contains("need_key")),
                            "the connection test has not come back: " + told);
                })
                .thenExecute(() -> {
                    var catalog = told.stream().filter(l -> l.kind().equals(com.dwinovo.numen.api.brain.Libraries.CATALOG))
                            .findFirst();
                    Checks.assertTrue(helper, catalog.isPresent() && catalog.get().problem().isEmpty()
                                    && catalog.get().entries().contains("\"vendors\"")
                                    && catalog.get().entries().contains("\"preview\"")
                                    && catalog.get().entries().contains("\"models\""),
                            "the catalog was not answered whole: " + catalog);
                    var checks = told.stream()
                            .filter(l -> l.kind().equals(com.dwinovo.numen.api.brain.Libraries.PROVIDER_CHECK)).toList();
                    Checks.assertTrue(helper, checks.size() == 2 && checks.get(0).request() == 0
                                    && checks.get(0).entries().contains("checking"),
                            "the test did not first say it was trying, under no request: " + checks);
                    Checks.assertTrue(helper, checks.get(1).request() == 9 && checks.get(1).entries().contains("need_key"),
                            "the outcome did not come back under the request number: " + checks);
                    // 答复是目录与连通性检测这两类表;同批别的用例增删档案时推给每个人的档案表不算
                    Checks.assertTrue(helper, GameTestKit.received(other).stream().noneMatch(p ->
                                    p instanceof com.dwinovo.numen.api.network.payload.LibraryPayload l
                                            && (l.kind().equals(com.dwinovo.numen.api.brain.Libraries.CATALOG)
                                            || l.kind().equals(com.dwinovo.numen.api.brain.Libraries.PROVIDER_CHECK))),
                            "the answer reached a player who did not ask");
                    server.getPlayerList().deop(admin.getGameProfile());
                    GameTestKit.leave(other);
                    GameTestKit.leave(admin);
                })
                .thenSucceed();
    }
}
