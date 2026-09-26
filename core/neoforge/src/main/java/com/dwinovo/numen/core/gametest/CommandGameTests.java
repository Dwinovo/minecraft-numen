package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.Authority;
import com.dwinovo.numen.cli.OnHer;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenCommands;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.ConsentRequest;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskDispatch;
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
 * {@code command} 工具:她执行一行命令,两层都从服务端同一个执行入口过。行首带 {@code /} 的是第 0 层(MC 的指令树,
 * 原版与模组的指令):先按她的来源解析(写不通当场失败、附用法),再过权限层({@code command(根名)}),再以她的身份执行,
 * 回显就是回执。不带 {@code /} 的是第 1 层(Numen 给她的命令层),在 Numen 自己的调度器上执行。
 *
 * <ul>
 *   <li>第 0 层能用哪些是服务器按她的权限等级定的(测试里直接把她记进 OP 表,等级 2);主人的允许与拒绝规则直接生效,
 *       没有规则说到的问主人;出厂规则放行只读与只说话的指令。</li>
 *   <li>她的第 1 层命令不在 MC 的指令树上;{@code /numen} 只剩管理同伴的指令,只给玩家——她经第 0 层敲不到。</li>
 *   <li>同一个名字两层都有也不冲突(夹具组 {@code gt_twin} 与一条同名的原生指令)。</li>
 *   <li>长活从 {@code command} 派出,受理与收尾都对着这次调用的 id 与"组 动作"这个名字。</li>
 *   <li>{@code /numen drive} 与 {@code command} 是同一个入口,两层的结果都一样;同步短活的最终回执、主人点过头的原生
 *       指令的回执也回到 drive 的发令人(夹具组 {@code gt_sync} 的 {@code hold} 是一件 runSync 的短活)。</li>
 *   <li>{@code /help <指令>} 在原版用法之后接上从 Brigadier 挖出的参数类型、例子与候选;写错了接上最接近的候选。</li>
 * </ul>
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CommandGameTests {

    private static final Param<Integer> TICKS = Param.required("ticks", ArgType.integer(1, 100),
            "How long to hold still, in ticks.");
    /** 夹具组与同名原生指令共用的名字。 */
    private static final String TWIN = "gt_twin";

    static {
        NumenPlugins.register(numen -> numen.registerCommands("gt_sync",
                "Test fixture: a short action the caller waits on.", g ->
                        g.server("hold", "Hold still for a few ticks while the caller waits.",
                                (src, args) -> TaskDispatch.runSync(src.companion(),
                                        new HoldRecord(src, args.get(TICKS)), src::reply),
                                TICKS)
                                .example("gt_sync hold 5")));
        NumenPlugins.register(numen -> numen.registerCommands(TWIN,
                "Test fixture: a group that shares its name with a native command.", g -> {
                    g.server("ping", "Say which layer answered.",
                                    (src, args) -> src.reply(TaskResult.ok("layer one").toJson()))
                            .example(TWIN + " ping");
                    g.server("mark", "Mark yourself through the native admin command.", (src, args) -> {
                                OnHer her = src.onHer();
                                List<String> words = her.next(TWIN + " mark");
                                List<String> said = her.run(TWIN + " mark", words.get(0));
                                src.reply(TaskResult.ok(String.join(",", words) + " | " + String.join(" ", said))
                                        .toJson());
                            })
                            .authority(Authority.SERVER_ON_HER)
                            .example(TWIN + " mark");
                }));
        // 一个不守输出预算的动作:回执比一个下行包还大,测网络层接得住
        NumenPlugins.register(numen -> numen.registerCommands("gt_wire",
                "Test fixture: an action whose reply is bigger than one payload to the client.", g ->
                        g.server("flood", "Reply with more text than one payload carries.",
                                (src, args) -> src.reply(TaskResult.ok(
                                        "x".repeat(com.dwinovo.numen.network.Wire.TO_CLIENT.bytes() + 1)).toJson()))
                                .example("gt_wire flood")));
        TaskFactory.register(HoldRecord.class, (body, record) -> new Hold(record));
    }

    /** 夹具的同步短活:站着数够刻数就干完。名字与调用 id 取自派它的那次调用。 */
    private static final class HoldRecord extends TaskRecord {
        final int ticks;

        HoldRecord(ServerSource source, int ticks) {
            super(source, source.companion().level().getGameTime() + ticks + 100);
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
    private static void cleanUp(GameTestHelper helper, NumenPlayer companion, NumenPlayer owner) {
        companion.getServer().getPlayerList().getOps().remove(companion.getGameProfile());
        CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        if (owner != null) {
            CompanionFactory.despawn(helper.getLevel().getServer(), owner);
        }
    }

    private static PermissionStore storeOf(NumenPlayer owner) {
        return PermissionStore.of(owner.getServer(), owner.getUUID());
    }

    private static String message(String reply) {
        return JsonParser.parseString(reply).getAsJsonObject().get("message").getAsString();
    }

    private static String setblock(GameTestHelper helper, BlockPos rel) {
        BlockPos at = helper.absolutePos(rel);
        return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:stone";
    }

    /** 服务器不让她用:没有 OP 时 give 当场如实失败,说清是服务器不让;不问主人,背包不变。 */
    /**
     * 真机事故那一类:回执比一个下行包大。从网络入口进来({@code ExecuteToolPayload.handle},和主人的客户端发来的一样),
     * 回执经 {@code NumenNetwork} 送主人:整条路不抛异常、不断开;送出去的是同一次调用的一条失败回执,说清多大、上限多少、
     * 怎么要少一点,而且编得进一个包。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void an_oversized_result_reaches_the_owner_as_a_failure_not_a_disconnect(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_flooder", new BlockPos(2, 2, 2), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_flood_owner");
        ServerLevel level = helper.getLevel();

        com.dwinovo.numen.network.payload.ExecuteToolPayload.handle(
                new com.dwinovo.numen.network.payload.ExecuteToolPayload(companion.getUUID(), "gt-flood",
                        com.dwinovo.numen.cli.CommandTool.NAME, "{\"command\":\"gt_wire flood\"}"), owner);

        ToolRun run = command(companion, "gt_wire flood");
        var sent = com.dwinovo.numen.network.Wire.TO_CLIENT.fit(
                com.dwinovo.numen.network.payload.TaskResultPayload.STREAM_CODEC,
                new com.dwinovo.numen.network.payload.TaskResultPayload(companion.getUUID(), "gt-flood", run.reply()),
                () -> new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),
                        level.registryAccess()));
        JsonObject result = JsonParser.parseString(sent.resultJson()).getAsJsonObject();
        helper.assertTrue("gt-flood".equals(sent.toolCallId()) && !result.get("success").getAsBoolean()
                        && result.get("message").getAsString().startsWith("The result of this call came to ")
                        && result.get("message").getAsString().contains("--page"),
                "the oversized result is not replaced by a failure that says so: " + sent.resultJson());
        helper.assertTrue(result.getAsJsonObject("data").get("limit_bytes").getAsInt()
                        == com.dwinovo.numen.network.Wire.TO_CLIENT.bytes(),
                "the failure does not name the limit: " + sent.resultJson());
        CompanionFactory.despawn(level.getServer(), companion);
        CompanionFactory.despawn(level.getServer(), owner);
        helper.succeed();
    }

    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_without_op_is_refused_by_the_server(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_guest", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_host");
        ToolRun give = command(companion, "/give @s minecraft:diamond");

        helper.succeedWhen(() -> {
            helper.assertTrue(give.task() == null, "a command the server refuses must not reach the task slot");
            helper.assertTrue(give.reply() != null && !give.succeeded()
                            && give.reply().contains("the server does not let you use /give"),
                    "no-op give did not fail with the reason: " + give.reply());
            helper.assertTrue(ConsentDesk.of(companion).pending() == null, "asked the owner about a refused command");
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 0, "got a diamond without op");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 有 OP、主人允许 give:不问、当场执行,背包里多了钻石,回执里是服务器的原话;执行一行指令不是身体上的活,不进任务槽。
     * 参数写错的当场失败,附上这条的用法。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_give_with_op_and_an_allow_rule_runs_and_echoes(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_op", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_admin");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.ALLOW, Rule.parse("command(give)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun give = command(companion, "/give @s minecraft:diamond 2");
        ToolRun typo = command(companion, "/give @s minecraft:not_an_item");

        helper.succeedWhen(() -> {
            helper.assertTrue(give.done(), "give has not finished");
            helper.assertTrue(give.succeeded(), "give failed: " + give.outcome());
            helper.assertTrue(give.task() == null, "a game command occupied the task slot: " + give.task());
            helper.assertTrue(give.outcome().contains("Gave 2 [Diamond] to gametest_mc_op"),
                    "the reply does not carry the server's echo: " + give.outcome());
            helper.assertTrue(companion.getInventory().countItem(Items.DIAMOND) == 2, "no diamonds in the inventory");
            helper.assertTrue(!asked[0], "an allowed command still asked the owner");
            helper.assertTrue(typo.task() == null && !typo.succeeded()
                            && typo.reply().contains("Usage: /give <targets> <item> [<count>]"),
                    "a bad argument does not come back with the usage: " + typo.reply());
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 没有任何一行规则说到 setblock:动手前问主人,卡上是整行指令;挂着的这些刻世界不变;主人允许后才放下石头,
     * 回执带着服务器的回显与"主人允许了"。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_command")
    public static void command_setblock_without_a_rule_asks_the_owner_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_builder", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_landlord");
        grantOp(companion);
        String line = setblock(helper, target);
        ToolRun run = command(companion, "/" + line);
        int[] waited = new int[1];

        helper.succeedWhen(() -> {
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
            helper.assertTrue(run.outcome().contains("Changed the block")
                            && run.outcome().contains("the owner allowed"),
                    "the reply lacks the echo or the owner's allowance: " + run.outcome());
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 主人一行规则都没写:出厂层放行只读与只说话的指令,{@code /help} 与私信的别名 {@code /tell} 不弹卡、直接执行;
     * 没有规则说到的 setblock 照旧问,见 {@link #command_setblock_without_a_rule_asks_the_owner_first}。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_factory_rules_let_help_and_tell_run_without_asking(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_chatty", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gt_mc_listener");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun help = command(companion, "/help");
        ToolRun tell = command(companion, "/tell gt_mc_listener on my way");

        helper.succeedWhen(() -> {
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
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_strict");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.DENY, Rule.parse("command(setblock)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun run = command(companion, "/" + setblock(helper, target));

        helper.succeedWhen(() -> {
            helper.assertTrue(run.done(), "setblock has not settled");
            helper.assertTrue(!run.succeeded() && run.outcome().contains("denied by rule command(setblock)"),
                    "the refusal does not quote the rule: " + run.outcome());
            helper.assertTrue(!asked[0], "a denied command raised a consent card");
            helper.assertTrue(level.getBlockState(helper.absolutePos(target)).isAir(), "a denied setblock ran");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * 原版 {@code /help} 按她的来源过滤,列的就是她此刻能执行的:没有 OP 时有 msg、没有 give,也没有 {@code /numen}
     * (那是玩家的管理指令);有 OP 后 give 也在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_lists_only_what_the_server_lets_her_run(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_reader", new BlockPos(4, 2, 4), false);
        String guest = message(command(companion, "/help").reply());
        helper.assertTrue(guest.contains("/msg <targets> <message>") && !guest.contains("/give "),
                "the no-op help lists the wrong commands: " + guest);
        helper.assertTrue(!guest.contains("/numen"), "her help shows the players' /numen: " + guest);

        grantOp(companion);
        String op = message(command(companion, "/help").reply());
        helper.assertTrue(op.contains("/give <targets> <item> [<count>]"), "the op help never lists /give: " + op);
        helper.assertTrue(!op.contains("/numen"), "her op help shows the players' /numen: " + op);
        cleanUp(helper, companion, null);
        helper.succeed();
    }

    /**
     * 她的第 1 层命令不在 MC 的指令树上:树根下没有她的命令组,{@code /numen} 下只有管理同伴的指令,玩家(哪怕是 OP)
     * 看得见的里面没有一格是她的,整棵树上也没有一种 Numen 自己的参数类型,发给玩家的指令树包造得出来。{@code /numen}
     * 整个根她都用不了:经第 0 层敲玩家的管理指令,当场如实失败,不问主人,什么都没发生。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_her_commands_are_off_the_mc_tree_and_numen_is_the_players(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_viewer", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_viewed");
        var server = helper.getLevel().getServer();
        CommandNode<CommandSourceStack> root = server.getCommands().getDispatcher().getRoot();
        CommandNode<CommandSourceStack> numen = root.getChild(NumenCommands.ROOT);
        CommandSourceStack player = server.createCommandSourceStack()
                .withEntity(helper.makeMockPlayer(GameType.SURVIVAL)).withPermission(4);
        List<String> hers = List.of("task", "gt_long", "gt_sync", "help", "--help");

        helper.assertTrue(List.of("task", "gt_long", "gt_sync").stream().noneMatch(g -> root.getChild(g) != null),
                "a Numen command group is a root of the MC tree");
        Set<String> verbs = usable(numen, player);
        helper.assertTrue(verbs.containsAll(List.of("player", "settings", "reset", "permission", "consent", "drive")),
                "a player's /numen lacks the verbs: " + verbs);
        helper.assertTrue(hers.stream().noneMatch(verbs::contains), "a player's /numen carries her commands: " + verbs);
        helper.assertTrue(!numen.canUse(companion.createCommandSourceStack()), "she can use /numen");
        Set<Class<?>> types = new HashSet<>();
        argumentTypes(root, player, types);
        helper.assertTrue(types.stream().allMatch(ArgumentTypeInfos::isClassRecognized),
                "the MC tree carries an argument type the registry does not know: " + types);
        server.getCommands().sendCommands(companion);

        ToolRun summon = command(companion, "/numen player summon gametest_mc_twin");
        ToolRun drive = command(companion, "/numen drive gametest_mc_viewer /help");
        for (ToolRun refused : List.of(summon, drive)) {
            helper.assertTrue(!refused.succeeded() && refused.task() == null
                            && refused.reply().contains("the server does not let you use /numen"),
                    "a player's verb was not refused on layer 0: " + refused.reply());
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
     * 同一个名字两层都有,互不干扰:不带 {@code /} 的是她的第 1 层命令组,带 {@code /} 的是同名的原生指令,各答各的。
     * 原生那条照第 0 层的规矩过权限层(主人允许了 {@code command(gt_twin)})。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_one_name_on_both_layers_does_not_collide(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_twin_caller", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_twin_owner");
        registerNativeTwin(helper.getLevel().getServer());
        storeOf(owner).add(Verdict.Kind.ALLOW, Rule.parse("command(" + TWIN + ")"));
        ToolRun one = command(companion, TWIN + " ping");
        ToolRun zero = command(companion, "/" + TWIN + " ping");

        helper.assertTrue(one.succeeded() && message(one.reply()).equals("layer one"),
                "layer 1 did not answer its own line: " + one.reply());
        helper.assertTrue(zero.succeeded() && message(zero.reply()).equals("ran /" + TWIN + " ping: layer zero"),
                "layer 0 did not answer the native line: " + zero.reply());
        cleanUp(helper, companion, owner);
        helper.succeed();
    }

    /**
     * 与夹具组同名的原生指令:{@code ping} 谁都能用;{@code mark <玩家> <词>} 是一条要 OP 2 级的管理指令,能对任何玩家
     * 用,词的补全是 alpha、beta。
     */
    private static void registerNativeTwin(net.minecraft.server.MinecraftServer server) {
        server.getCommands().getDispatcher().register(Commands.literal(TWIN)
                .then(Commands.literal("ping").executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("layer zero"), false);
                    return 1;
                }))
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
     * 借服务器的权威:夹具组的 {@code mark} 声明了 {@link Authority#SERVER_ON_HER},她没有 OP 也执行得了那条要 OP 的原生
     * 管理指令,补全也读得到;作用对象是她自己,不问主人。她自己在第 0 层敲同一条,服务器不让。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_a_wrapper_borrows_the_servers_authority_only_on_her(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gt_mc_marked", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gt_mc_marker");
        registerNativeTwin(helper.getLevel().getServer());
        ToolRun wrapped = command(companion, TWIN + " mark");
        ToolRun herself = command(companion, "/" + TWIN + " mark gt_mc_marked alpha");

        helper.assertTrue(wrapped.succeeded()
                        && message(wrapped.reply()).equals("alpha,beta | marked gt_mc_marked alpha"),
                "the wrapper did not run on her with the server's authority: " + wrapped.reply());
        helper.assertTrue(!herself.succeeded() && herself.task() == null,
                "she ran the admin command with her own authority: " + herself.reply());
        helper.assertTrue(ConsentDesk.of(companion).pending() == null, "the wrapper asked the owner");
        cleanUp(helper, companion, owner);
        helper.succeed();
    }

    /**
     * 长活从 {@code command} 派出:受理回执对着这次调用(调度器按调用 id 认得出它派下的活),任务叫"组 动作",
     * 收尾的 task_finished 用的是受理时那个任务号与名字。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_long_work_is_accepted_and_finished_under_one_id(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_worker", new BlockPos(4, 2, 4), false);
        ToolRun run = command(companion, "gt_long linger 10");
        EventOutbox outbox = EventOutbox.get(helper.getLevel().getServer());

        helper.succeedWhen(() -> {
            helper.assertTrue(run.task() != null, "the long work was not found under the call's id: " + run.reply());
            JsonObject data = JsonParser.parseString(run.reply()).getAsJsonObject().getAsJsonObject("data");
            String id = data.get("task_id").getAsString();
            helper.assertTrue(id.equals(run.task().publicId()) && data.get("task").getAsString().equals("gt_long linger"),
                    "the receipt names another task: " + run.reply());
            helper.assertTrue(outbox.peek(companion.getUUID()).entries().stream()
                            .anyMatch(e -> e.type().equals("task_finished") && e.text().contains(id)
                                    && e.text().contains("task=\"gt_long linger\"")),
                    "task_finished does not answer the receipt: " + outbox.peek(companion.getUUID()).entries());
            outbox.forget(companion.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * {@code /numen drive <同伴> <一行指令>} 与 {@code command} 是同一个入口,两层都一样:同一行,回执一样;写不通的说法
     * 也一样。回执说给发 drive 的人听(这里是控制台)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_drive_runs_a_line_through_her_entry(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_driven", new BlockPos(4, 2, 4), false);
        var server = helper.getLevel().getServer();
        List<String> lines = List.of("task status", "task stauts", "/help help", "/gvie @s stone");
        List<ToolRun> viaCommand = lines.stream().map(line -> command(companion, line)).toList();
        List<String> heard = new ArrayList<>();
        CommandSourceStack console = console(server, heard);
        for (String line : lines) {
            server.getCommands().performPrefixedCommand(console, "/numen drive gametest_mc_driven " + line);
        }

        helper.succeedWhen(() -> {
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == lines.size(), "drive did not answer every line: " + heard);
            for (int i = 0; i < lines.size(); i++) {
                helper.assertTrue(heard.get(i).equals(name + ": " + message(viaCommand.get(i).reply())),
                        "drive and command differ on " + lines.get(i) + ": " + heard.get(i) + " / "
                                + viaCommand.get(i).reply());
            }
            helper.assertTrue(viaCommand.get(0).succeeded() && !viaCommand.get(1).succeeded()
                            && viaCommand.get(2).succeeded() && !viaCommand.get(3).succeeded(),
                    "the lines did not come out as written: " + viaCommand);
            CompanionFactory.despawn(server, companion);
        });
    }

    /** 控制台那样的发令人:她那一行的回执说给它听,一句一条记进 {@code heard}。 */
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
     * 经 drive 派的同步短活:结算后的最终回执回到 drive 的发令人那里,恰好一条——结果只有派它的那次调用这一个去处。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_drive_hears_the_final_result_of_a_sync_action(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_held", new BlockPos(4, 2, 4), false);
        var server = helper.getLevel().getServer();
        List<String> heard = new ArrayList<>();
        server.getCommands().performPrefixedCommand(console(server, heard),
                "numen drive gametest_mc_held gt_sync hold 5");

        helper.succeedWhen(() -> {
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 1, "drive did not hear exactly one final result: " + heard);
            helper.assertTrue(heard.get(0).equals(name + ": held for 5 ticks"),
                    "drive heard another result: " + heard.get(0));
            CompanionFactory.despawn(server, companion);
        });
    }

    /**
     * 经 drive 执行、要主人点头的原生指令:主人答复之前发令人什么都没听到;允许后才执行,回执回到 drive 的发令人那里,
     * 恰好一条,末尾交代主人允许了什么。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_command")
    public static void command_drive_hears_a_native_line_the_owner_allowed(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_placer", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_placer_owner");
        grantOp(companion);
        var server = level.getServer();
        List<String> heard = new ArrayList<>();
        server.getCommands().performPrefixedCommand(console(server, heard),
                "numen drive gametest_mc_placer /" + setblock(helper, target));
        boolean[] allowed = new boolean[1];

        helper.succeedWhen(() -> {
            if (!allowed[0]) {
                ConsentRequest pending = ConsentDesk.of(companion).pending();
                helper.assertTrue(pending != null, "the driven line did not ask the owner: " + heard);
                helper.assertTrue(heard.isEmpty(), "drive heard something before the owner answered: " + heard);
                ConsentDesk.of(companion).answer(pending.id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
                allowed[0] = true;
                helper.fail("allowed; waiting for the line to run");
            }
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 1, "drive did not hear exactly one result: " + heard);
            helper.assertTrue(heard.get(0).startsWith(name + ": ran /setblock")
                            && heard.get(0).contains("the owner allowed"),
                    "drive heard another result, or it lacks the owner's allowance: " + heard.get(0));
            helper.assertTrue(level.getBlockState(helper.absolutePos(target)).is(Blocks.STONE), "no stone was set");
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * {@code /help give}:原版那一行用法之后,是从 Brigadier 挖出的参数类型与类型自带的例子,再是此刻接下来能写的
     * (她自己、选择器);写了半截的物品 id 只列以它开头的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_give_mines_types_examples_and_candidates(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_learner", new BlockPos(4, 2, 4), false);
        grantOp(companion);
        ToolRun give = command(companion, "/help give");
        ToolRun item = command(companion, "/help give @s minecraft:diamond_");
        ToolRun groups = command(companion, "help");
        String said = message(give.reply());
        String items = message(item.reply());
        String listed = message(groups.reply());
        Constants.LOG.info("[numen-cli] /help give -> {}", said);
        Constants.LOG.info("[numen-cli] /help give @s minecraft:diamond_ -> {}", items);
        Constants.LOG.info("[numen-cli] help -> {}", listed);

        helper.assertTrue(groups.succeeded() && listed.startsWith("<group> <action> [arguments]. Command groups:\n")
                        && listed.contains("\n  task — "),
                "help without the / is not layer 1's own listing: " + listed);
        helper.assertTrue(give.succeeded() && said.startsWith("ran /help give: /give <targets> <item> [<count>]\n"),
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
     * 写错了:报错仍是 Brigadier 的原话、位置与那一层的用法,最后接上最接近的候选——原版指令的物品 id 与 Numen 命令的
     * 动作名是同一个函数。写不通的当场失败,不进任务槽。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_a_typo_ends_with_the_nearest_candidate(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_typist", new BlockPos(4, 2, 4), false);
        grantOp(companion);
        ToolRun item = command(companion, "/give @s minecraft:dimond");
        ToolRun action = command(companion, "gt_long lingre 40");
        String itemSaid = message(item.reply());
        String actionSaid = message(action.reply());
        Constants.LOG.info("[numen-cli] /give @s minecraft:dimond -> {}", itemSaid);
        Constants.LOG.info("[numen-cli] gt_long lingre 40 -> {}", actionSaid);

        helper.assertTrue(!item.succeeded() && item.task() == null
                        && itemSaid.contains("minecraft:dimond") && itemSaid.contains("<--[HERE]")
                        && itemSaid.contains("\nUsage: /give <targets> <item> [<count>]")
                        && itemSaid.endsWith("\nDid you mean: minecraft:diamond?"),
                "the item typo does not end with the nearest item: " + itemSaid);
        helper.assertTrue(!action.succeeded() && action.task() == null
                        && actionSaid.contains("<--[HERE]") && actionSaid.contains("gt_long linger <ticks>")
                        && actionSaid.endsWith("\nDid you mean: linger?"),
                "the action typo does not end with the nearest action: " + actionSaid);
        cleanUp(helper, companion, null);
        helper.succeed();
    }
}
