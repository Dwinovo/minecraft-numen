package com.dwinovo.numen.plugins.minecraft.gametest;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.Mind;
import com.dwinovo.numen.api.owner.OwnerInput;
import com.dwinovo.numen.api.owner.OwnerInputs;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.GameBrain;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.dwinovo.numen.plugins.minecraft.gametest.GameTestKit.programCall;
import static com.dwinovo.numen.plugins.minecraft.gametest.GameTestKit.spawnAt;
import static com.dwinovo.numen.plugins.minecraft.gametest.GameTestKit.succeedWhen;

/**
 * 服务端与主人客户端上她的大脑({@link com.dwinovo.numen.api.mind.LoopHost})之间的整条路:座位、握手、程序在服务端跑、回执回到模型,
 * 以及召唤。大脑里的事(日志、札记、日程、对话、库)在大脑那一头的单测里,这里只测要世界在场才有意义的。模型是脚本化的({@link Scripted}),
 * 其余全是产品里的部件。
 */
public class BrainGameTests {

    // 每个批次都自己定下世界(难度、时刻、天气、模拟距离、权限设置),不继承上一批留下的:批次的先后说不准
    @BeforeBatch(batch = "numen_brain")
    public static void prepareBrainBatch(ServerLevel level) {
        GameTestKit.settleWorld(level, Difficulty.PEACEFUL, GameTestKit.NOON);
    }

    /** 按事先排好的回复答的"模型";记下每次收到的请求。 */
    static final class Scripted implements Mind {
        final Deque<ModelOutcome> replies = new ArrayDeque<>();
        final List<ModelRequest> asked = new ArrayList<>();

        Scripted say(String text) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
            return this;
        }

        Scripted program(String code) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn("", List.of(programCall(code)), null), Usage.ZERO));
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

    /** 主人的话 → 模型派一段程序 → 程序经座位在服务端跑 → 回执回到模型 → 最终回复,整条回路都落进她的日志;大脑是主人客户端上的那一份。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void owner_words_run_the_whole_loop_through_the_seat(GameTestHelper helper) {
        Scripted mind = new Scripted().program("return 1 + 1").say("算好了:2");
        NumenPlayer her = spawnAt(helper, "gametest_thinker", new BlockPos(3, 2, 3), false);
        GameBrain brain = GameBrain.sit(helper.getLevel().getServer(), her, mind);
        boolean[] spoken = {false};

        succeedWhen(helper, () -> {
            helper.assertTrue(brain.ready(), "her brain has not taken the seat");
            if (!spoken[0]) {
                brain.host().submitPrompt("算一下 1 + 1");
                spoken[0] = true;
            }
            helper.assertTrue(mind.asked.size() == 2, "the model was asked " + mind.asked.size() + " time(s)");
            List<ConvoState.Msg> t = brain.host().log().loadLines(100).stream()
                    .map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::msg)
                    .filter(m -> !(m instanceof ConvoState.Msg.User u && u.content().startsWith("<events>"))).toList();
            helper.assertTrue(t.size() == 4 && t.get(0) instanceof ConvoState.Msg.User
                            && t.get(1) instanceof ConvoState.Msg.Assistant a && a.turn().hasToolCalls()
                            && t.get(2) instanceof ConvoState.Msg.Tool && t.get(3) instanceof ConvoState.Msg.Assistant,
                    "the log does not hold user, call, receipt, reply: " + t);
            ConvoState.Msg.Tool receipt = (ConvoState.Msg.Tool) t.get(2);
            helper.assertTrue(receipt.content().contains("2"), "the receipt does not carry the program's value: "
                    + receipt.content());
            helper.assertTrue(mind.asked.get(1).messages().stream().anyMatch(m -> m instanceof ConvoState.Msg.Tool),
                    "the second request did not carry the receipt");
            helper.assertTrue(mind.asked.get(0).messages().stream().anyMatch(m -> m instanceof ConvoState.Msg.User u
                            && u.content().startsWith("<runtime_state>")),
                    "the request did not carry the state the server gave at the start of the run");
            brain.close();
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 轨迹文件里的一行。只给这些用例读,不做通用的读取器。 */
    record TraceRecord(com.google.gson.JsonObject json) {
        String type() {
            return json.get("type").getAsString();
        }

        long run() {
            return json.get("run").getAsLong();
        }

        int n() {
            return json.get("n").getAsInt();
        }

        String string(String key) {
            return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
        }

        static List<TraceRecord> readAll(java.nio.file.Path file) throws java.io.IOException {
            List<TraceRecord> out = new ArrayList<>();
            if (!java.nio.file.Files.isRegularFile(file)) {
                return out;
            }
            for (String line : java.nio.file.Files.readAllLines(file)) {
                if (line.isBlank()) {
                    continue;
                }
                com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                if (o.has("type") && "header".equals(o.get("type").getAsString())) {
                    continue;
                }
                out.add(new TraceRecord(o));
            }
            return out;
        }
    }

    /** 这一只轨迹里 {@code type} 那一类的记录。 */
    private static List<TraceRecord> traceRecords(GameBrain brain, String type) {
        try {
            return TraceRecord.readAll(brain.host().trace().file()).stream().filter(r -> r.type().equals(type)).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 她身体这一本轨迹里 {@code type} 那一类的记录;身体的轨迹经服务端的写车道落盘,还没落下来就是空的。 */
    private static List<TraceRecord> bodyTraceRecords(NumenPlayer her, String type) {
        java.nio.file.Path file = com.dwinovo.numen.api.entity.BodyTrace.of(her).file();
        if (file == null || !java.nio.file.Files.exists(file)) {
            return List.of();
        }
        try {
            return TraceRecord.readAll(file).stream().filter(r -> r.type().equals(type)).toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 寻路的"为什么"落到她身体的轨迹文件里:服务端的寻路写服务端那一本,不经她的大脑。 */
    @GameTest(template = "floor16", timeoutTicks = 600, batch = "numen_brain")
    public static void aPathThatGivesUpLandsInHerTrace(GameTestHelper helper) {
        com.dwinovo.numen.api.agent.trace.TraceRecorders.record();
        NumenPlayer her = spawnAt(helper, "gametest_stuck", new BlockPos(3, 2, 3), false);
        GameBrain brain = GameBrain.sit(helper.getLevel().getServer(), her, new Scripted());
        // 模板坐标先换成世界坐标:setBlock 收的是相对模板的,tick 里身体站在世界里
        BlockPos feet = helper.absolutePos(new BlockPos(8, 1, 8));
        // 四面与头顶围成基岩盒子:她出不去,这一趟必然收场并诊断出"没有路"
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    boolean shell = dx == -2 || dx == 2 || dy == 0 || dy == 3 || dz == -2 || dz == 2;
                    helper.getLevel().setBlockAndUpdate(feet.offset(dx, dy, dz),
                            shell ? net.minecraft.world.level.block.Blocks.BEDROCK.defaultBlockState()
                                    : net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
            }
        }
        her.teleportTo(helper.getLevel(), feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, 0f, 0f);
        BlockPos goal = feet.offset(12, 0, 0);

        var nav = com.dwinovo.numen.plugins.minecraft.nav.CompanionPorts.navigator(her,
                        com.dwinovo.numen.plugins.minecraft.nav.CompanionPorts.dangers(her), List.of())
                .drive(com.dwinovo.numen.plugins.minecraft.pathing.api.NavRequest.to(
                        com.dwinovo.numen.plugins.minecraft.pathing.search.Goals.at(goal),
                        com.dwinovo.numen.plugins.minecraft.pathing.spec.RouteSpec.defaults()));

        succeedWhen(helper, () -> {
            var status = nav.tick();
            helper.assertTrue(!status.running(), "这一趟还没收场: " + status);
            List<TraceRecord> paths = bodyTraceRecords(her, "path");
            helper.assertTrue(!paths.isEmpty(), "寻路的一条都没落进她身体的轨迹,结局是 " + status);
            TraceRecord last = paths.get(paths.size() - 1);
            helper.assertTrue(!"Arrived".equals(last.string("outcome")), "围死了却说到了: " + last.json());
            helper.assertTrue(last.string("outcome") != null, "收场那条没结局: " + last.json());
            brain.close();
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
    }

    /** 每一轮模型请求都留一份"她当时看到了什么":发出去的那份运行期状态原文,一字不多一字不少。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void everyTurnLeavesWhatSheSawOnDisk(GameTestHelper helper) {
        com.dwinovo.numen.api.agent.trace.TraceRecorders.record();
        Scripted mind = new Scripted().say("看到了");
        NumenPlayer her = spawnAt(helper, "gametest_seer", new BlockPos(3, 2, 3), false);
        GameBrain brain = GameBrain.sit(helper.getLevel().getServer(), her, mind);
        boolean[] spoken = {false};

        succeedWhen(helper, () -> {
            helper.assertTrue(brain.ready(), "her brain has not taken the seat");
            if (!spoken[0]) {
                brain.host().submitPrompt("你周围有什么");
                spoken[0] = true;
            }
            List<TraceRecord> turns = traceRecords(brain, "turn");
            helper.assertTrue(turns.size() == 1, "one turn, got " + turns.size());
            TraceRecord turn = turns.get(0);
            helper.assertTrue(turn.n() == 1, "the first turn of the run is 1, got " + turn.n());
            helper.assertTrue(turn.run() > 0, "the turn does not carry its run");
            String sent = mind.asked.get(0).messages().stream()
                    .filter(m -> m instanceof ConvoState.Msg.User u && u.content().startsWith("<runtime_state>"))
                    .map(m -> ((ConvoState.Msg.User) m).content()).findFirst().orElse("");
            helper.assertTrue(!sent.isEmpty(), "the request did not carry a runtime_state at all");
            helper.assertTrue(sent.equals(turn.string("runtime")), "what was recorded is not what was sent");
            brain.close();
            CompanionFactory.despawn(helper.getLevel().getServer(), her);
        });
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
                        && opener.protocol() == com.dwinovo.numen.api.owner.Presence.PROTOCOL)
                .toList();
        helper.assertTrue(sent.size() == 1, "the opener was not sent once when the player joined: " + sent);
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /**
     * 装了客户端的主人进服:报到之前服务端只发开场那一包(协议号),名册也等着;报到之后得到回答(是不是管理员)、名册,
     * 并补上会话列表,整条路经真的发包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_client_that_says_hello_is_answered_and_caught_up(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_greeter");
        NumenPlayer her = com.dwinovo.numen.api.gametest.Bodies.summon(owner.server, owner.getUUID(), "gametest_greeted",
                helper.getLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        // 开场在平台的登入或频道登记那一刻发;再调一次不重发
        com.dwinovo.numen.api.platform.PlayerSessions.channelReady(owner);
        com.dwinovo.numen.api.entity.Companions.syncRosterToOwner(owner.server, owner);

        var before = GameTestKit.received(owner).stream()
                .filter(p -> p.type().id().getNamespace().equals(com.dwinovo.numen.api.Constants.MOD_ID)).toList();
        helper.assertTrue(before.size() == 1
                        && before.get(0) instanceof com.dwinovo.numen.api.network.payload.ServerHelloPayload opener
                        && opener.protocol() == com.dwinovo.numen.api.owner.Presence.PROTOCOL,
                "before the hello the server sent more than its opener: " + before);

        com.dwinovo.numen.api.owner.Replies.hello(owner, com.dwinovo.numen.api.owner.Presence.PROTOCOL);

        var sent = GameTestKit.received(owner);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.HelloAckPayload),
                "no hello answer for an ordinary player: " + sent);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.CompanionListPayload),
                "the roster was not sent after the hello: " + sent);
        helper.assertTrue(sent.stream().anyMatch(p -> p instanceof com.dwinovo.numen.api.network.payload.ConversationsPayload),
                "the conversation list was not sent");
        com.dwinovo.numen.api.entity.Companions.dismiss(owner.server, owner.getUUID(), List.of(her.getUUID()));
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /**
     * 面板上的加人与退出(上行的 {@link OwnerInput.Share}、{@link OwnerInput.Unshare})和命令落在同一处:创建者按名字加一位朋友,
     * 结局照命令的那句话回给他,表上的每一位都收到带着整张主人表的名册(创建者在前);朋友加不了人;朋友退出后创建者收到的名册上
     * 她的主人表只剩他自己,朋友的名册上没有她了。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void the_panel_shares_and_leaves_through_the_same_door_as_the_command(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        var creator = GameTestKit.presentPlayer(helper, null, "gt_panel_owner");
        var friend = GameTestKit.presentPlayer(helper, null, "gt_panel_pal");
        com.dwinovo.numen.api.owner.Replies.hello(creator, com.dwinovo.numen.api.owner.Presence.PROTOCOL);
        com.dwinovo.numen.api.owner.Replies.hello(friend, com.dwinovo.numen.api.owner.Presence.PROTOCOL);
        NumenPlayer her = com.dwinovo.numen.api.gametest.Bodies.summon(server, creator.getUUID(), "gt_panel_her", level,
                net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        UUID id = her.getUUID();
        var registry = CompanionRegistry.get(server);
        GameTestKit.received(creator);
        GameTestKit.received(friend);
        GameTestKit.said(creator);

        helper.assertTrue(OwnerInputs.apply(server, friend.getUUID(), new OwnerInput.Share(id, "gt_panel_owner")).problem() != null
                        && registry.find(id).keepers().equals(List.of(creator.getUUID())),
                "someone who is not her creator could share her");
        helper.assertTrue(OwnerInputs.apply(server, creator.getUUID(), new OwnerInput.Share(id, "gt_panel_pal")).problem() == null
                        && registry.find(id).keepers().equals(List.of(creator.getUUID(), friend.getUUID())),
                "the creator's share from the panel did not put the friend on her owners");
        helper.assertTrue(GameTestKit.said(creator).stream().anyMatch(s -> ClientlessGameTests.isKey(s.text(),
                        com.dwinovo.numen.api.data.ModLanguageData.Keys.COMMAND_SHARED)),
                "the creator was not told what the command would have told him");
        var table = List.of(new com.dwinovo.numen.api.entity.Keeper(creator.getUUID(), "gt_panel_owner"),
                new com.dwinovo.numen.api.entity.Keeper(friend.getUUID(), "gt_panel_pal"));
        for (var keeper : List.of(creator, friend)) {
            helper.assertTrue(table.equals(ownersOnRoster(keeper, id)),
                    keeper.getGameProfile().getName() + " was not sent her whole owner table, creator first");
        }

        helper.assertTrue(OwnerInputs.apply(server, friend.getUUID(), new OwnerInput.Unshare(id, friend.getUUID())).problem() == null
                        && registry.find(id).keepers().equals(List.of(creator.getUUID())),
                "the friend's leave from the panel did not take him off her owners");
        helper.assertTrue(GameTestKit.said(friend).stream().anyMatch(s -> ClientlessGameTests.isKey(s.text(),
                        com.dwinovo.numen.api.data.ModLanguageData.Keys.COMMAND_LEFT)),
                "the friend was not told he left");
        helper.assertTrue(table.subList(0, 1).equals(ownersOnRoster(creator, id)),
                "the creator's roster still lists the friend who left");
        helper.assertTrue(ownersOnRoster(friend, id) == null, "she is still on the roster of the friend who left");

        com.dwinovo.numen.api.entity.Companions.dismiss(server, creator.getUUID(), List.of(id));
        GameTestKit.leave(creator);
        GameTestKit.leave(friend);
        helper.succeed();
    }

    /** 这位玩家最近收到的那份名册上她的主人表;没收到名册、或名册上没有她是 {@code null}。取走他收到的包。 */
    private static List<com.dwinovo.numen.api.entity.Keeper> ownersOnRoster(net.minecraft.server.level.ServerPlayer player,
                                                                             UUID companion) {
        List<com.dwinovo.numen.api.entity.Keeper> owners = null;
        for (var payload : GameTestKit.received(player)) {
            if (payload instanceof com.dwinovo.numen.api.network.payload.CompanionListPayload roster) {
                owners = roster.companions().stream().filter(row -> row.uuid().equals(companion))
                        .map(com.dwinovo.numen.api.network.payload.CompanionListPayload.Entry::keepers).findFirst().orElse(null);
            }
        }
        return owners;
    }

    /** 协议版本对不上的客户端不被服务:开场那包已经告诉过它服务端的版本,之后什么也不发——回答、名册、她的东西都不发。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_brain")
    public static void a_client_of_another_protocol_is_sent_nothing_more(GameTestHelper helper) throws Exception {
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_mismatch");
        NumenPlayer her = com.dwinovo.numen.api.gametest.Bodies.summon(owner.server, owner.getUUID(), "gametest_unserved",
                helper.getLevel(), net.minecraft.world.phys.Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(8, 2, 8))));
        GameTestKit.received(owner);

        com.dwinovo.numen.api.owner.Replies.hello(owner, com.dwinovo.numen.api.owner.Presence.PROTOCOL + 1);
        com.dwinovo.numen.api.entity.Companions.syncRosterToOwner(owner.server, owner);

        var sent = GameTestKit.received(owner);
        helper.assertTrue(sent.isEmpty(), "an unserved client was sent something: " + sent);
        com.dwinovo.numen.api.entity.Companions.dismiss(owner.server, owner.getUUID(), List.of(her.getUUID()));
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /**
     * 召唤只要名字,不设前提:什么都没选的召唤,服务端只造出身体、登记进名册,她的创建者是主人;大脑与库绑定在主人的客户端上,不在这里。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_brain")
    public static void summoning_takes_only_a_name(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_summoner");

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_born",
                        com.dwinovo.numen.api.entity.SummonSetup.NONE), owner);

        var born = server.getPlayerList().getPlayerByName("gametest_born");
        helper.assertTrue(born instanceof NumenPlayer, "the companion was not summoned");
        helper.assertTrue(CompanionRegistry.get(server).find(born.getUUID()) != null
                        && CompanionRegistry.get(server).find(born.getUUID()).owner().equals(owner.getUUID()),
                "she is not on the roster as her owner's");
        helper.assertTrue(!born.isCreative(), "she arrived in creative without being asked");

        com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(born.getUUID()));
        GameTestKit.leave(owner);
        helper.succeed();
    }

    /** 召唤卡上选的模式随召唤请求一起来:她到达时已经落下,和事后在编辑卡上改走的是同一条路。主人在创造,创造的门是开的。 */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_brain")
    public static void a_summon_request_with_a_setup_has_her_in_that_mode_as_she_arrives(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var owner = GameTestKit.presentPlayer(helper, null, "gametest_setup_owner");
        owner.setGameMode(net.minecraft.world.level.GameType.CREATIVE);

        com.dwinovo.numen.api.network.payload.SummonRequestPayload.handle(
                new com.dwinovo.numen.api.network.payload.SummonRequestPayload("gametest_setup",
                        new com.dwinovo.numen.api.entity.SummonSetup(true)), owner);

        var born = server.getPlayerList().getPlayerByName("gametest_setup");
        helper.assertTrue(born instanceof NumenPlayer, "the companion was not summoned");
        helper.assertTrue(born.isCreative(), "she did not arrive in the mode picked on the card");

        com.dwinovo.numen.api.entity.Companions.dismiss(server, owner.getUUID(), List.of(born.getUUID()));
        GameTestKit.leave(owner);
        helper.succeed();
    }
}
