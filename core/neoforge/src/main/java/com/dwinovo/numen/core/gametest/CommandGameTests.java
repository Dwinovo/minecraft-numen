package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenCommands;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.ConsentRequest;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.OnHer;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskFactory;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 她怎么执行原版与模组的指令({@code numen.mc.run}),以及一次 API 调用从哪些入口进来、结果怎么回去。
 *
 * <ul>
 *   <li>{@code numen.mc.run} 先按她的来源解析(写不通当场失败、附用法),再过权限层({@code command(根名)}),再以她的身份执行,
 *       交回指令说的每一句。能用哪些是服务器按她的权限等级定的(测试里直接把她记进 OP 表,等级 2);主人的允许与拒绝规则直接生效,
 *       没有规则说到的问主人,点了头的那一句随值写进程序的回执;出厂规则放行只读与只说话的指令。</li>
 *   <li>{@code /numen} 只有管理同伴的指令,只给玩家——她经 {@code numen.mc.run} 敲不到。</li>
 *   <li>包装模组管理指令的函数经 {@link ServerCall#onHer} 借服务器的权威、只作用于她(夹具组 {@code gt_twin})。</li>
 *   <li>占身体的活受理时回它的编号,收尾的 task_finished 对着同一个编号与函数名。</li>
 *   <li>{@code /numen drive <同伴> <程序>} 和她自己写的程序同一个入口;一件短活的账、主人点过头的指令都写进那张回执,回到 drive 的
 *       发令人(夹具组 {@code gt_sync} 的 {@code hold} 是一件有界短活)。</li>
 *   <li>{@code numen.mc.run("help <指令>")} 在原版用法之后接上从 Brigadier 挖出的参数类型、例子与候选;写错了接上最接近的候选。</li>
 * </ul>
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CommandGameTests {

    /** 夹具组与同名原生指令共用的名字。 */
    private static final String TWIN = "gt_twin";

    static {
        if (GameTestKit.numenTestsEnabled()) {
            NumenPlugins.register("gt", numen -> {
                numen.api("gt_sync", "Test fixture: a short action the caller waits on.", SyncWork.class);
                numen.api(TWIN, "Test fixture: a wrapper of a native admin command.", Twin.class);
                // 一个不守输出预算的函数:回执比一个下行包还大,测网络层接得住
                numen.api("gt_wire", "Test fixture: a value bigger than one payload to the client.", Flood.class);
            });
            TaskFactory.register(HoldRecord.class, (body, record) -> new Hold(record));
        }
    }

    /** 夹具组 {@code gt.gt_sync}:一件调用等着它做完的短活。 */
    public static final class SyncWork {

        private SyncWork() {}

        /** 站多久。 */
        public record Ticks(@Doc("How long to hold still, in ticks (1-100).") int ticks) {}

        @Fn("Hold still for a few ticks while the caller waits.")
        @Example("gt.gt_sync.hold(5)")
        public static Pending<Void> hold(ServerCall call, Ticks args) {
            return call.sync(new HoldRecord(call, Math.clamp(args.ticks(), 1, 100)));
        }
    }

    /** 夹具组 {@code gt.gt_twin}:以服务器的权威、只对她执行一条要 OP 的原生管理指令。 */
    public static final class Twin {

        private Twin() {}

        @Fn("Mark yourself through the native admin command, with the server's authority on you only.")
        @Example("gt.gt_twin.mark()")
        public static String mark(ServerCall call) {
            OnHer her = call.onHer();
            List<String> words = her.next(TWIN + " mark");
            List<String> said = her.run(TWIN + " mark", words.get(0));
            return String.join(",", words) + " | " + String.join(" ", said);
        }
    }

    /** 夹具组 {@code gt.gt_wire}:交回的值比一个下行包大。 */
    public static final class Flood {

        private Flood() {}

        @Fn("Return more text than one payload to the client carries.")
        @Example("gt.gt_wire.flood()")
        public static String flood(ServerCall call) {
            return "x".repeat(com.dwinovo.numen.network.Wire.TO_CLIENT.bytes() + 1);
        }
    }

    /** 夹具的短活:站着数够刻数就干完。名字与调用 id 取自派它的那次调用。 */
    private static final class HoldRecord extends TaskRecord {
        final int ticks;

        HoldRecord(ServerCall call, int ticks) {
            super(call, call.her().level().getGameTime() + ticks + 100);
            this.ticks = ticks;
        }
    }

    private static final class Hold implements Task {
        private final HoldRecord record;
        private int held;

        Hold(HoldRecord record) {
            this.record = record;
        }

        @Override
        public TaskState tick(NumenPlayer companion) {
            return ++held >= record.ticks ? TaskState.SUCCESS : TaskState.RUNNING;
        }

        @Override
        public void stop(NumenPlayer companion, StopReason why) {
        }

        @Override
        public TaskResult result(TaskState terminal) {
            return terminal == TaskState.SUCCESS ? TaskResult.ok("held for " + held + " ticks")
                    : TaskResult.fail("stopped after " + held + " ticks");
        }

        @Override
        public String name() {
            return "hold";
        }
    }

    /** 指令批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_command")
    public static void prepareCommandBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /**
     * 给她 OP,等级 2(能用 give、setblock 这一档)。测试服的 {@code op} 按服务器设定给 0 级,所以直接写进 OP 表——
     * 这就是服主给她的等级。
     */
    private static void grantOp(NumenPlayer companion) {
        companion.getServer().getPlayerList().getOps()
                .add(new ServerOpListEntry(companion.getGameProfile(), 2, false));
    }

    /** 收回 OP 并送走两具身体:OP 表会落盘,不能留着测试的人。 */
    private static void cleanUp(GameTestHelper helper, NumenPlayer companion,
                                net.minecraft.server.level.ServerPlayer owner) {
        companion.getServer().getPlayerList().getOps().remove(companion.getGameProfile());
        CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        if (owner != null) {
            leave(owner);
        }
    }

    private static PermissionStore storeOf(net.minecraft.server.level.ServerPlayer owner) {
        return PermissionStore.of(owner.getServer(), owner.getUUID());
    }

    /** {@code numen.mc.run} 交回的指令说的话,一句一行。 */
    private static String said(ToolRun run) {
        return run.field("output") instanceof List<?> lines
                ? lines.stream().map(String::valueOf).collect(Collectors.joining("\n")) : "";
    }

    private static String setblock(GameTestHelper helper, BlockPos rel) {
        BlockPos at = helper.absolutePos(rel);
        return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:stone";
    }

    /**
     * 真机事故那一类:一次调用交回的值比一个下行包大。程序跑在服务端,值只在服务端里传,不上线;回执是给模型读的,按构造有界——
     * 返回值在回执里截到上限并写明原来多长。整张回执经 {@code OwnerLine} 走主人的连接送出去(服务端的 {@code RunProgramPayload}
     * 处理函数,和主人客户端发来的一样),一路不抛异常、不断开,而且编得进一个包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void an_oversized_value_reaches_the_owner_as_a_bounded_receipt_not_a_disconnect(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_flooder", new BlockPos(2, 2, 2), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_flood_owner");
        ServerLevel level = helper.getLevel();
        java.util.concurrent.atomic.AtomicReference<com.dwinovo.numen.program.RunResult> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.dwinovo.numen.program.ProgramUplink uplink = new com.dwinovo.numen.program.ProgramUplink(
                new com.dwinovo.numen.program.ModuleSync(), payload ->
                        com.dwinovo.numen.network.payload.RunProgramPayload.handle(
                                (com.dwinovo.numen.network.payload.RunProgramPayload) payload, owner),
                com.dwinovo.numen.script.Modules::of);
        uplink.run(companion.getUUID(), "gt-flood", "return gt.gt_wire.flood()", result::set);

        steps(helper)
                .thenWaitUntil(() -> {
                    for (var payload : received(owner)) {
                        if (payload instanceof com.dwinovo.numen.network.payload.ProgramResultPayload answer) {
                            uplink.deliver(answer.programId(),
                                    com.dwinovo.numen.program.RunResult.fromJson(answer.resultJson()));
                        }
                    }
                    helper.assertTrue(result.get() != null, "the receipt has not come back");
                })
                .thenExecute(() -> {
                    String receipt = ((com.dwinovo.numen.program.RunResult.Ended) result.get()).outcome().receipt();
                    JsonObject data = JsonParser.parseString(receipt).getAsJsonObject();
                    helper.assertTrue(data.get("success").getAsBoolean(), "the program failed: " + receipt);
                    helper.assertTrue(receipt.length() < com.dwinovo.numen.agent.script.ScriptLimits.RETURNED_CHARS + 500,
                            "the receipt is not bounded: " + receipt.length() + " characters");
                    helper.assertTrue(data.get("message").getAsString().contains("[returned value cut at "
                                    + com.dwinovo.numen.agent.script.ScriptLimits.RETURNED_CHARS + " characters; it was "
                                    + (com.dwinovo.numen.network.Wire.TO_CLIENT.bytes() + 1) + "]"),
                            "the receipt does not say the value was cut: " + receipt.substring(0, Math.min(400, receipt.length())));
                    CompanionFactory.despawn(level.getServer(), companion);
                    leave(owner);
                })
                .thenSucceed();
    }

    /** 服务器不让她用:没有 OP 时 give 当场如实失败,说清是服务器不让;不问主人,背包不变。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_without_op_is_refused_by_the_server(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_guest", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_host");
        ToolRun give = lua(companion, "numen.mc.run(\"give @s minecraft:diamond\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(give.task() == null, "a command the server refuses must not reach the task slot");
            helper.assertTrue(give.refused() && "bad_argument".equals(give.kind())
                            && give.outcome().contains("the server does not let you use /give"),
                    "no-op give did not fail with the reason: " + give.reply());
            helper.assertTrue(ConsentDesk.of(companion).pending() == null, "asked the owner about a refused command");
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "got a diamond without op");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 有 OP、主人允许 give:不问、当场执行,背包里多了钻石,交回的是服务器的原话;执行一行指令不是身体上的活,不进任务槽。
     * 参数写错的当场失败,附上这条的用法。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_give_with_op_and_an_allow_rule_runs_and_echoes(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_op", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_admin");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.ALLOW, Rule.parse("command(give)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun give = lua(companion, "numen.mc.run(\"give @s minecraft:diamond 2\")");
        ToolRun typo = lua(companion, "numen.mc.run(\"give @s minecraft:not_an_item\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(give.done(), "give has not finished");
            helper.assertTrue(give.succeeded(), "give failed: " + give.outcome());
            helper.assertTrue(give.task() == null, "a game command occupied the task slot: " + give.task());
            helper.assertTrue(said(give).contains("Gave 2 [Diamond] to gametest_mc_op")
                            && "/give @s minecraft:diamond 2".equals(give.field("command")),
                    "the value does not carry the server's echo: " + give.reply());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 2, "no diamonds in the inventory");
            helper.assertTrue(!asked[0], "an allowed command still asked the owner");
            helper.assertTrue(typo.refused()
                            && typo.outcome().contains("usage: numen.mc.run(\"give <targets> <item> [<count>]\")"),
                    "a bad argument does not come back with the usage: " + typo.outcome());
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 没有任何一行规则说到 setblock:动手前问主人,卡上是整行指令;挂着的这些刻世界不变;主人允许后才放下石头,
     * 交回服务器的回显,主人允许了什么写进程序的回执。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_command")
    public static void command_setblock_without_a_rule_asks_the_owner_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_builder", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_landlord");
        grantOp(companion);
        String line = setblock(helper, target);
        ToolRun run = lua(companion, "numen.mc.run(\"" + line + "\")");
        int[] waited = new int[1];

        succeedWhen(helper, () -> {
            if (waited[0] < 10) {
                ConsentRequest pending = ConsentDesk.of(companion).pending();
                helper.assertTrue(pending != null, "setblock did not ask: " + run.outcome());
                ConsentItem item = pending.items().get(0);
                helper.assertTrue(item.kind() == Action.Kind.COMMAND
                                && item.name().getString().equals("/" + line) && item.icon() == null,
                        "the card does not show the command: " + item);
                helper.assertTrue("command(setblock)".equals(item.remember().toString()),
                        "remembering would not store the root: " + item.remember());
                helper.assertTrue(level.getBlockState(helper.absolutePos(target)).isAir(),
                        "the block was set before the owner answered");
                helper.assertTrue(!run.done(), "the call settled without an answer");
                if (++waited[0] == 10) {
                    ConsentDesk.of(companion).answer(pending.id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
                }
                helper.fail("still waiting on purpose");
            }
            helper.assertTrue(run.done() && run.succeeded(), "setblock did not finish: " + run.outcome());
            helper.assertTrue(level.getBlockState(helper.absolutePos(target)).is(Blocks.STONE), "no stone was set");
            helper.assertTrue(said(run).contains("Changed the block"), "the value lacks the echo: " + run.reply());
            helper.assertTrue(run.outcome().contains("the owner allowed")
                            && run.receipt().contains("the owner allowed"),
                    "the receipt does not tell what the owner allowed: " + run.receipt());
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 主人一行规则都没写:出厂层放行只读与只说话的指令,{@code help} 与私信的别名 {@code tell} 不弹卡、直接执行;
     * 没有规则说到的 setblock 照旧问,见 {@link #command_setblock_without_a_rule_asks_the_owner_first}。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_factory_rules_let_help_and_tell_run_without_asking(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_chatty", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gt_mc_listener");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun help = lua(companion, "numen.mc.run(\"help\")");
        ToolRun tell = lua(companion, "numen.mc.run(\"tell gt_mc_listener on my way\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(help.done() && help.succeeded(), "help did not run: " + help.outcome());
            helper.assertTrue(tell.done() && tell.succeeded(), "tell did not run: " + tell.outcome());
            helper.assertTrue(!asked[0], "a factory-allowed command asked the owner");
            cleanUp(helper, companion, owner);
        });
    }

    /** 主人写了拒绝 setblock 的规则:当场如实失败、理由是那一行,不弹卡,世界不变。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_setblock_denied_by_a_rule_fails_with_the_rule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_forbidden", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_strict");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.DENY, Rule.parse("command(setblock)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun run = lua(companion, "numen.mc.run(\"" + setblock(helper, target) + "\")");

        succeedWhen(helper, () -> {
            helper.assertTrue(run.done(), "setblock has not settled");
            helper.assertTrue(!run.succeeded() && "denied".equals(run.kind())
                            && run.outcome().contains("denied by rule command(setblock)"),
                    "the refusal does not quote the rule: " + run.outcome());
            helper.assertTrue(!asked[0], "a denied command raised a consent card");
            helper.assertTrue(level.getBlockState(helper.absolutePos(target)).isAir(), "a denied setblock ran");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 原版 {@code help} 按她的来源过滤,列的就是她此刻能执行的:没有 OP 时有 msg、没有 give,也没有 {@code /numen}
     * (那是玩家的管理指令);有 OP 后 give 也在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_lists_only_what_the_server_lets_her_run(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_reader", new BlockPos(4, 2, 4), false);
        String guest = said(lua(companion, "numen.mc.run(\"help\")"));
        helper.assertTrue(guest.contains("/msg <targets> <message>") && !guest.contains("/give "),
                "the no-op help lists the wrong commands: " + guest);
        helper.assertTrue(!guest.contains("/numen"), "her help shows the players' /numen: " + guest);

        grantOp(companion);
        String op = said(lua(companion, "numen.mc.run(\"help\")"));
        helper.assertTrue(op.contains("/give <targets> <item> [<count>]"), "the op help never lists /give: " + op);
        helper.assertTrue(!op.contains("/numen"), "her op help shows the players' /numen: " + op);
        cleanUp(helper, companion, null);
        helper.succeed();
    }

    /**
     * {@code /numen} 只有管理同伴的指令,只给玩家:玩家(哪怕是 OP)看得见的里面没有一格是她的 API,整棵树上也没有一种 Numen
     * 自己的参数类型,发给玩家的指令树包造得出来。{@code /numen} 整个根她都用不了:经 {@code numen.mc.run} 敲玩家的管理指令,
     * 当场如实失败,不问主人,什么都没发生。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_numen_is_the_players_not_hers(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_viewer", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_viewed");
        var server = helper.getLevel().getServer();
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        CommandNode<CommandSourceStack> numen = root.getChild(NumenCommands.ROOT);
        CommandSourceStack player = server.createCommandSourceStack()
                .withEntity(helper.makeMockPlayer(GameType.SURVIVAL)).withPermission(4);

        Set<String> verbs = usable(numen, player);
        helper.assertTrue(verbs.containsAll(List.of("player", "settings", "reset", "permission", "consent", "drive"))
                        && List.of("task", "api", "mc", "move", "work").stream().noneMatch(verbs::contains),
                "a player's /numen is not the admin verbs, or carries her API: " + verbs);
        helper.assertTrue(!numen.canUse(companion.createCommandSourceStack()), "she can use /numen");
        Set<Class<?>> types = new HashSet<>();
        argumentTypes(root, player, types);
        helper.assertTrue(types.stream().allMatch(ArgumentTypeInfos::isClassRecognized),
                "the MC tree carries an argument type the registry does not know: " + types);
        server.getCommands().sendCommands(companion);

        ToolRun summon = lua(companion, "numen.mc.run(\"numen player summon gametest_mc_twin\")");
        ToolRun drive = lua(companion, "numen.mc.run(\"numen drive gametest_mc_viewer numen.task.status()\")");
        for (ToolRun refused : List.of(summon, drive)) {
            helper.assertTrue(refused.refused() && refused.outcome().contains("the server does not let you use /numen"),
                    "a player's verb was not refused: " + refused.reply());
        }
        helper.assertTrue(ConsentDesk.of(companion).pending() == null, "asked the owner about a player's verb");
        helper.assertTrue(server.getPlayerList().getPlayerByName("gametest_mc_twin") == null,
                "she summoned a companion");
        cleanUp(helper, companion, owner);
        helper.succeed();
    }

    /** 这个来源用得了的那些格。 */
    private static Set<String> usable(CommandNode<CommandSourceStack> node, CommandSourceStack source) {
        return node.getChildren().stream().filter(c -> c.canUse(source)).map(CommandNode::getName)
                .collect(Collectors.toSet());
    }

    /** 这个来源用得了的节点上的每种参数类型(发指令树包时要按类在注册表里查到它)。 */
    private static void argumentTypes(CommandNode<CommandSourceStack> node, CommandSourceStack source,
                                      Set<Class<?>> found) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (!child.canUse(source)) {
                continue;
            }
            if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
                found.add(argument.getType().getClass());
            }
            argumentTypes(child, source, found);
        }
    }

    /**
     * 与夹具组同名的原生指令:{@code mark <玩家> <词>} 是一条要 OP 2 级的管理指令,能对任何玩家用,词的补全是 alpha、beta。
     */
    private static void registerNativeTwin(net.minecraft.server.MinecraftServer server) {
        server.getCommands().getDispatcher().register(Commands.literal(TWIN)
                .then(Commands.literal("mark").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("word", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                List.of("alpha", "beta"), builder))
                                        .executes(ctx -> {
                                            String target = EntityArgument.getPlayer(ctx, "target").getName()
                                                    .getString();
                                            String word = StringArgumentType.getString(ctx, "word");
                                            ctx.getSource().sendSuccess(
                                                    () -> Component.literal("marked " + target + " " + word), false);
                                            return 1;
                                        })))));
    }

    /**
     * 借服务器的权威:夹具组的 {@code mark} 经 {@link ServerCall#onHer} 执行那条要 OP 的原生管理指令,她没有 OP 也执行得了,补全也
     * 读得到;作用对象是她自己,不问主人。她自己经 {@code numen.mc.run} 敲同一条,服务器不让。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_a_wrapper_borrows_the_servers_authority_only_on_her(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gt_mc_marked", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gt_mc_marker");
        registerNativeTwin(helper.getLevel().getServer());
        ToolRun wrapped = lua(companion, "gt." + TWIN + ".mark()");
        ToolRun herself = lua(companion, "numen.mc.run(\"" + TWIN + " mark gt_mc_marked alpha\")");

        helper.assertTrue(wrapped.succeeded() && "alpha,beta | marked gt_mc_marked alpha".equals(wrapped.value()),
                "the wrapper did not run on her with the server's authority: " + wrapped.reply());
        helper.assertTrue(herself.refused(), "she ran the admin command with her own authority: " + herself.reply());
        helper.assertTrue(ConsentDesk.of(companion).pending() == null, "the wrapper asked the owner");
        cleanUp(helper, companion, owner);
        helper.succeed();
    }

    /**
     * 占身体的活从脚本里派出:受理时交回活的编号(调度器按调用 id 认得出它派下的活),任务叫那个函数名,收尾的 task_finished 用的是
     * 受理时那个编号与名字。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_long_work_is_accepted_and_finished_under_one_id(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_worker", new BlockPos(4, 2, 4), false);
        ToolRun run = lua(companion, "gt.gt_long.linger(10)");
        EventOutbox outbox = EventOutbox.get(helper.getLevel().getServer());

        succeedWhen(helper, () -> {
            helper.assertTrue(run.task() != null, "the long work was not found under the call's id: " + run.reply());
            String id = JsonParser.parseString(run.reply()).getAsJsonObject().get("job").getAsString();
            helper.assertTrue(id.equals(run.task().publicId())
                            && run.task().getToolName().equals("gt.gt_long.linger"),
                    "the reply names another task: " + run.reply());
            // 程序等着的这件活的收尾写在它的回执里(编号就是受理时回的那个),不另发事件
            helper.assertTrue(run.receipt() != null
                            && run.receipt().contains("line 1 gt.gt_long.linger: stood for 10 ticks"),
                    "the receipt does not answer the reply with the task's end: " + run.receipt());
            outbox.forget(companion.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * {@code /numen drive <同伴> <程序>} 是人敲的那个前端:同一张登记表、同一个入口,程序跑完的回执说给发 drive 的人听(这里是控制台),
     * 跑得通的跑到底,写不通的停在出错的那一行、说为什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_drive_runs_a_program_through_her_entry(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_driven", new BlockPos(4, 2, 4), false);
        var server = helper.getLevel().getServer();
        List<String> programs = List.of("numen.task.status()", "numen.task.stauts()", "numen.mc.run(\"help help\")",
                "numen.mc.run(\"gvie @s stone\")");
        List<String> heard = new ArrayList<>();
        CommandSourceStack console = console(server, heard);
        // 一只同伴同一刻只跑一段:一段的回执说完,才敲下一段
        Steps sequence = steps(helper);
        for (int i = 0; i < programs.size(); i++) {
            int n = i;
            sequence = sequence
                    .thenExecute(() -> server.getCommands().performPrefixedCommand(console,
                            "/numen drive gametest_mc_driven " + programs.get(n)))
                    .thenWaitUntil(() -> helper.assertTrue(heard.size() > n, "drive has not answered " + programs.get(n)));
        }
        sequence.thenExecute(() -> {
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == programs.size(), "drive did not answer every program: " + heard);
            for (int i : new int[]{0, 2}) {
                helper.assertTrue(heard.get(i).startsWith(name + ": ok · 1 call"),
                        "drive did not run " + programs.get(i) + " to the end: " + heard.get(i));
            }
            helper.assertTrue(heard.get(1).startsWith(name + ": The script stopped at line 1")
                            && heard.get(1).contains("there is no API function numen.task.stauts")
                            && heard.get(1).contains("numen.task.status"),
                    "drive did not say which function it could not find: " + heard.get(1));
            helper.assertTrue(heard.get(3).startsWith(name + ": The script stopped at line 1")
                            && heard.get(3).contains("bad_argument") && heard.get(3).contains("/gvie"),
                    "drive did not say which command it could not read: " + heard.get(3));
            CompanionFactory.despawn(server, companion);
        }).thenSucceed();
    }

    /** 控制台那样的发令人:她那一段的回执说给它听,一句一条记进 {@code heard}。 */
    private static CommandSourceStack console(net.minecraft.server.MinecraftServer server, List<String> heard) {
        return server.createCommandSourceStack().withSource(new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                heard.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        });
    }

    /**
     * 经 drive 派的有界短活:做完之后的回执回到 drive 的发令人那里,恰好一条,那件短活的账写在里面。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_drive_hears_the_account_of_a_short_action(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_held", new BlockPos(4, 2, 4), false);
        var server = helper.getLevel().getServer();
        List<String> heard = new ArrayList<>();
        server.getCommands().performPrefixedCommand(console(server, heard),
                "numen drive gametest_mc_held gt.gt_sync.hold(5)");

        succeedWhen(helper, () -> {
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 1, "drive did not hear exactly one receipt: " + heard);
            helper.assertTrue(heard.get(0).startsWith(name + ": ok · ")
                            && heard.get(0).contains("line 1 gt.gt_sync.hold: held for 5 ticks"),
                    "drive did not hear the short action's account: " + heard.get(0));
            CompanionFactory.despawn(server, companion);
        });
    }

    /**
     * 经 drive 执行、要主人点头的原生指令:主人答复之前发令人什么都没听到;允许后才执行,回执回到 drive 的发令人那里,
     * 恰好一条,写着主人允许了什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_command")
    public static void command_drive_hears_a_native_line_the_owner_allowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_placer", new BlockPos(4, 2, 4), false);
        net.minecraft.server.level.ServerPlayer owner = presentOwner(helper, companion, "gametest_mc_placer_owner");
        grantOp(companion);
        var server = level.getServer();
        List<String> heard = new ArrayList<>();
        server.getCommands().performPrefixedCommand(console(server, heard),
                "numen drive gametest_mc_placer numen.mc.run(\"" + setblock(helper, target) + "\")");
        boolean[] allowed = new boolean[1];

        succeedWhen(helper, () -> {
            if (!allowed[0]) {
                ConsentRequest pending = ConsentDesk.of(companion).pending();
                helper.assertTrue(pending != null, "the driven line did not ask the owner: " + heard);
                helper.assertTrue(heard.isEmpty(), "drive heard something before the owner answered: " + heard);
                ConsentDesk.of(companion).answer(pending.id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
                allowed[0] = true;
                helper.fail("allowed; waiting for the line to run");
            }
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 1, "drive did not hear exactly one receipt: " + heard);
            helper.assertTrue(heard.get(0).startsWith(name + ": ok · ")
                            && heard.get(0).contains("the owner allowed"),
                    "drive heard another receipt, or it lacks the owner's allowance: " + heard.get(0));
            helper.assertTrue(level.getBlockState(helper.absolutePos(target)).is(Blocks.STONE), "no stone was set");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * {@code help give}:原版那一行用法之后,是从 Brigadier 挖出的参数类型与类型自带的例子,再是此刻接下来能写的
     * (她自己、选择器);写了半截的物品 id 只列以它开头的。{@code numen.api.help} 一组给的是那一组的签名。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_give_mines_types_examples_and_candidates(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_learner", new BlockPos(4, 2, 4), false);
        grantOp(companion);
        ToolRun give = lua(companion, "numen.mc.run(\"help give\")");
        ToolRun item = lua(companion, "numen.mc.run(\"help give @s minecraft:diamond_\")");
        ToolRun groups = lua(companion, "numen.api.help(\"numen.task\")");
        String said = said(give);
        String items = said(item);
        String listed = String.valueOf(groups.value());
        Constants.LOG.info("[numen-mc] help give -> {}", said);
        Constants.LOG.info("[numen-mc] help give @s minecraft:diamond_ -> {}", items);
        Constants.LOG.info("[numen-mc] numen.api.help(task) -> {}", listed);

        helper.assertTrue(groups.succeeded() && listed.contains("\n---@class numen.task\n")
                        && listed.contains("\n---@field status fun(") && listed.contains("\n---@field stop fun("),
                "numen.api.help of a group is not that group's own listing: " + listed);
        helper.assertTrue(give.succeeded() && said.startsWith("/give <targets> <item> [<count>]\n"),
                "the vanilla usage does not come first: " + said);
        helper.assertTrue(said.contains("\n  <targets> minecraft:entity (amount multiple, type players) — e.g. Player, ")
                        && said.contains("\n  <item> minecraft:item_stack — e.g. stick, minecraft:stick")
                        && said.contains("\n  <count> brigadier:integer (min 1"),
                "the argument types or their examples are missing: " + said);
        helper.assertTrue(said.contains("\nCan go next") && said.contains("@s"),
                "the candidates for <targets> are missing: " + said);
        String next = items.substring(items.indexOf("\nCan go next") + 1);
        helper.assertTrue(item.succeeded() && next.contains("minecraft:diamond_axe")
                        && Arrays.stream(next.substring(next.indexOf(": ") + 2).split(", "))
                                .allMatch(id -> id.startsWith("minecraft:diamond_")),
                "a half-written item id does not narrow the candidates: " + items);
        cleanUp(helper, companion, null);
        helper.succeed();
    }

    /**
     * 写错了:原版指令的报错仍是 Brigadier 的原话、位置与那一层的用法,最后接上最接近的物品 id;脚本里写错的函数名说没有这个
     * API 函数,接上最接近的那个。写不通的当场失败,不进任务槽。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_a_typo_ends_with_the_nearest_candidate(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_typist", new BlockPos(4, 2, 4), false);
        grantOp(companion);
        ToolRun item = lua(companion, "numen.mc.run(\"give @s minecraft:dimond\")");
        ToolRun action = lua(companion, "gt.gt_long.lingre(40)");
        String itemSaid = item.outcome();
        String actionSaid = action.outcome();
        Constants.LOG.info("[numen-mc] give @s minecraft:dimond -> {}", itemSaid);
        Constants.LOG.info("[numen-mc] gt.gt_long.lingre(40) -> {}", actionSaid);

        helper.assertTrue(item.refused()
                        && itemSaid.contains("minecraft:dimond") && itemSaid.contains("<--[HERE]")
                        && itemSaid.contains("\nusage: numen.mc.run(\"give <targets> <item> [<count>]\")")
                        && itemSaid.endsWith("\nhint: Did you mean: minecraft:diamond?"),
                "the item typo does not end with the nearest item: " + itemSaid);
        helper.assertTrue(!action.succeeded() && action.task() == null
                        && actionSaid.contains("stopped at line 1")
                        && actionSaid.contains("there is no API function gt.gt_long.lingre")
                        && actionSaid.contains("did you mean gt.gt_long.linger?"),
                "the function typo does not end with the nearest function: " + actionSaid);
        cleanUp(helper, companion, null);
        helper.succeed();
    }
}
