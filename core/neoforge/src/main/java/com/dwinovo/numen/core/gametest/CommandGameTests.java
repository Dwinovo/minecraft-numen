package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
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
import net.minecraft.commands.synchronization.ArgumentTypeInfos;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
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
 * {@code command} 工具:她执行一行游戏指令,Numen 自己的、原版的、模组的都从服务端同一个执行入口过——先按她的来源
 * 解析(写不通当场失败、附用法),再过权限层({@code command(根名)}),再以她的身份执行,回显就是回执。
 *
 * <ul>
 *   <li>能用哪些是服务器按她的权限等级定的(测试里直接把她记进 OP 表,等级 2);主人的允许与拒绝规则直接生效,没有规则
 *       说到的问主人;出厂规则放行只读与只说话的指令。</li>
 *   <li>{@code /numen} 下两种观众:她的命令组只给她,管理同伴的指令只给玩家;两边互相看不见。她的指令树造得出包
 *       (Numen 自己的参数类型登记过了)。</li>
 *   <li>长活从 {@code command} 派出,受理与收尾都对着这次调用的 id 与"组 动作"这个名字。</li>
 *   <li>{@code /numen drive} 与 {@code command} 是同一个入口,结果一样;同步短活的最终回执也回到 drive 的发令人
 *       (夹具组 {@code gt_sync} 的 {@code hold} 是一件 runSync 的短活)。</li>
 *   <li>{@code help <指令>} 在原版用法之后接上从 Brigadier 挖出的参数类型、例子与候选;写错了接上最接近的候选。</li>
 * </ul>
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CommandGameTests {

    private static final Param<Integer> TICKS = Param.required("ticks", ArgType.integer(1, 100),
            "How long to hold still, in ticks.");

    static {
        NumenPlugins.register(numen -> numen.registerCommands("gt_sync",
                "Test fixture: a short action the caller waits on.", g ->
                        g.server("hold", "Hold still for a few ticks while the caller waits.",
                                (src, args) -> TaskDispatch.runSync(src.companion(),
                                        new HoldRecord(src, args.get(TICKS)), src::reply),
                                TICKS)
                                .example("numen gt_sync hold 5")));
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
        ToolRun give = command(companion, "give @s minecraft:diamond 2");
        ToolRun typo = command(companion, "give @s minecraft:not_an_item");

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
        ToolRun run = command(companion, line);
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
     * 主人一行规则都没写:出厂层放行只读与只说话的指令,{@code help} 与私信的别名 {@code tell} 不弹卡、直接执行;
     * 没有规则说到的 setblock 照旧问,见 {@link #command_setblock_without_a_rule_asks_the_owner_first}。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_factory_rules_let_help_and_tell_run_without_asking(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_chatty", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gt_mc_listener");
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun help = command(companion, "help");
        ToolRun tell = command(companion, "tell gt_mc_listener on my way");

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
        ToolRun run = command(companion, setblock(helper, target));

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
     * 原版 {@code help} 按她的来源过滤,列的就是她此刻能执行的:没有 OP 时有 msg、没有 give,有她自己的
     * {@code /numen} 命令组、没有玩家的管理指令;有 OP 后 give 也在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_lists_only_what_the_server_lets_her_run(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_reader", new BlockPos(4, 2, 4), false);
        String guest = message(command(companion, "help").reply());
        helper.assertTrue(guest.contains("/msg <targets> <message>") && !guest.contains("/give "),
                "the no-op help lists the wrong commands: " + guest);
        helper.assertTrue(guest.contains("/numen ") && !guest.contains("permission") && !guest.contains("summon"),
                "her help does not show her own /numen, or shows a player's verbs: " + guest);

        grantOp(companion);
        String op = message(command(companion, "help").reply());
        helper.assertTrue(op.contains("/give <targets> <item> [<count>]"), "the op help never lists /give: " + op);
        cleanUp(helper, companion, null);
        helper.succeed();
    }

    /**
     * 可见性:{@code /numen} 下她看得见的只有她的命令组与帮助,玩家看得见的只有管理同伴的指令,两边不相交;玩家那一侧
     * 连一种 Numen 自己的参数类型都碰不到,装不装 Numen 客户端都不受影响。服务器给她造的指令树包造得出来——她的
     * 参数类型都登记过了。
     */
    @GameTest(template = "floor16", timeoutTicks = 100, batch = "numen_command")
    public static void command_numen_nodes_are_hers_and_the_verbs_are_the_players(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_viewer", new BlockPos(4, 2, 4), false);
        Commands commands = helper.getLevel().getServer().getCommands();
        CommandNode<CommandSourceStack> numen = commands.getDispatcher().getRoot().getChild(NumenCli.ROOT);
        CommandSourceStack player = helper.getLevel().getServer().createCommandSourceStack()
                .withEntity(helper.makeMockPlayer(GameType.SURVIVAL)).withPermission(0);

        Set<String> hers = usable(numen, companion.createCommandSourceStack());
        Set<String> players = usable(numen, player);
        helper.assertTrue(hers.containsAll(List.of("help", "--help", "task", "gt_long")),
                "her /numen lacks her groups: " + hers);
        helper.assertTrue(players.containsAll(List.of("player", "settings", "reset", "permission", "consent")),
                "a player's /numen lacks the verbs: " + players);
        Set<String> both = new HashSet<>(hers);
        both.retainAll(players);
        helper.assertTrue(both.isEmpty(), "seen by both her and a player: " + both);
        helper.assertTrue(!usable(numen, player.withPermission(2)).contains("task"),
                "an op player sees her groups");

        Set<String> playerTypes = new HashSet<>();
        argumentTypes(numen, player.withPermission(4), playerTypes);
        helper.assertTrue(playerTypes.stream().allMatch(CommandGameTests::vanillaArgumentType),
                "a player's tree carries a Numen argument type: " + playerTypes);
        Set<String> herTypes = new HashSet<>();
        argumentTypes(numen, companion.createCommandSourceStack(), herTypes);
        helper.assertTrue(herTypes.stream().anyMatch(t -> !vanillaArgumentType(t)),
                "her tree carries none of Numen's argument types: " + herTypes);

        commands.sendCommands(companion);
        CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        helper.succeed();
    }

    /** {@code /numen} 下这个来源用得了的那些格。 */
    private static Set<String> usable(CommandNode<CommandSourceStack> numen, CommandSourceStack source) {
        return numen.getChildren().stream().filter(c -> c.canUse(source)).map(CommandNode::getName)
                .collect(Collectors.toSet());
    }

    /** 这个来源用得了的节点上,每种参数类型在注册表里的名字(发指令树包时写进包里的就是它)。 */
    private static void argumentTypes(CommandNode<CommandSourceStack> node, CommandSourceStack source,
                                      Set<String> found) {
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            if (!child.canUse(source)) {
                continue;
            }
            if (child instanceof ArgumentCommandNode<CommandSourceStack, ?> argument) {
                found.add(String.valueOf(BuiltInRegistries.COMMAND_ARGUMENT_TYPE.getKey(
                        ArgumentTypeInfos.byClass(argument.getType()))));
            }
            argumentTypes(child, source, found);
        }
    }

    private static boolean vanillaArgumentType(String id) {
        return id.startsWith("minecraft:") || id.startsWith("brigadier:");
    }

    /**
     * 长活从 {@code command} 派出:受理回执对着这次调用(调度器按调用 id 认得出它派下的活),任务叫"组 动作",
     * 收尾的 task_finished 用的是受理时那个任务号与名字。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_long_work_is_accepted_and_finished_under_one_id(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_worker", new BlockPos(4, 2, 4), false);
        ToolRun run = command(companion, "numen gt_long linger 10");
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
     * {@code /numen drive <同伴> <一行指令>} 与 {@code command} 是同一个入口:同一行,回执一样;写不通的说法也一样。
     * 回执说给发 drive 的人听(这里是控制台)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_drive_runs_a_line_through_her_entry(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_driven", new BlockPos(4, 2, 4), false);
        var server = helper.getLevel().getServer();
        ToolRun status = command(companion, "numen task status");
        ToolRun typo = command(companion, "numen task stauts");
        List<String> heard = new ArrayList<>();
        CommandSourceStack console = console(server, heard);
        server.getCommands().performPrefixedCommand(console, "numen drive gametest_mc_driven numen task status");
        server.getCommands().performPrefixedCommand(console, "/numen drive gametest_mc_driven /numen task stauts");

        helper.succeedWhen(() -> {
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 2, "drive did not answer both lines: " + heard);
            helper.assertTrue(heard.get(0).equals(name + ": " + message(status.reply())),
                    "drive and command differ: " + heard.get(0) + " / " + status.reply());
            helper.assertTrue(heard.get(1).equals(name + ": " + message(typo.reply())),
                    "a mistake reads differently through drive: " + heard.get(1) + " / " + typo.reply());
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
     * 经 drive 放行、又要主人点头的同步短活:主人允许后活才开始,结算后的最终回执回到 drive 的发令人那里,恰好一条,
     * 末尾交代主人允许了什么——结果只有派它的那次调用这一个去处。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_command")
    public static void command_drive_hears_the_final_result_of_a_sync_action_the_owner_allowed(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_held", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_holder");
        storeOf(owner).add(Verdict.Kind.ASK, Rule.parse("command(numen)"));
        var server = helper.getLevel().getServer();
        List<String> heard = new ArrayList<>();
        server.getCommands().performPrefixedCommand(console(server, heard),
                "numen drive gametest_mc_held numen gt_sync hold 5");
        boolean[] allowed = new boolean[1];

        helper.succeedWhen(() -> {
            if (!allowed[0]) {
                ConsentRequest pending = ConsentDesk.of(companion).pending();
                helper.assertTrue(pending != null, "the driven line did not ask the owner: " + heard);
                helper.assertTrue(heard.isEmpty(), "drive heard something before the owner answered: " + heard);
                ConsentDesk.of(companion).answer(pending.id(), ConsentAnswer.Decision.ALLOW_ONCE, "");
                allowed[0] = true;
                helper.fail("allowed; waiting for the action to finish");
            }
            String name = companion.getName().getString();
            helper.assertTrue(heard.size() == 1, "drive did not hear exactly one final result: " + heard);
            helper.assertTrue(heard.get(0).startsWith(name + ": held for 5 ticks")
                            && heard.get(0).contains("the owner allowed"),
                    "drive heard another result, or it lacks the owner's allowance: " + heard.get(0));
            cleanUp(helper, companion, owner);
        });
    }

    /**
     * {@code help give}:原版那一行用法之后,是从 Brigadier 挖出的参数类型与类型自带的例子,再是此刻接下来能写的
     * (她自己、选择器);写了半截的物品 id 只列以它开头的。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_command")
    public static void command_help_give_mines_types_examples_and_candidates(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_learner", new BlockPos(4, 2, 4), false);
        grantOp(companion);
        ToolRun give = command(companion, "help give");
        ToolRun item = command(companion, "help give @s minecraft:diamond_");
        String said = message(give.reply());
        String items = message(item.reply());
        Constants.LOG.info("[numen-cli] help give -> {}", said);
        Constants.LOG.info("[numen-cli] help give @s minecraft:diamond_ -> {}", items);

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
        ToolRun item = command(companion, "give @s minecraft:dimond");
        ToolRun action = command(companion, "numen gt_long lingre 40");
        String itemSaid = message(item.reply());
        String actionSaid = message(action.reply());
        Constants.LOG.info("[numen-cli] give @s minecraft:dimond -> {}", itemSaid);
        Constants.LOG.info("[numen-cli] numen gt_long lingre 40 -> {}", actionSaid);

        helper.assertTrue(!item.succeeded() && item.task() == null
                        && itemSaid.contains("minecraft:dimond") && itemSaid.contains("<--[HERE]")
                        && itemSaid.contains("\nUsage: /give <targets> <item> [<count>]")
                        && itemSaid.endsWith("\nDid you mean: minecraft:diamond?"),
                "the item typo does not end with the nearest item: " + itemSaid);
        helper.assertTrue(!action.succeeded() && action.task() == null
                        && actionSaid.contains("<--[HERE]") && actionSaid.contains("numen gt_long linger <ticks>")
                        && actionSaid.endsWith("\nDid you mean: linger?"),
                "the action typo does not end with the nearest action: " + actionSaid);
        cleanUp(helper, companion, null);
        helper.succeed();
    }
}
