package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.ConsentAnswer;
import com.dwinovo.numen.permission.ConsentDesk;
import com.dwinovo.numen.permission.ConsentItem;
import com.dwinovo.numen.permission.ConsentRequest;
import com.dwinovo.numen.permission.PermissionStore;
import com.dwinovo.numen.permission.Rule;
import com.dwinovo.numen.permission.Verdict;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.ServerOpListEntry;
import net.minecraft.world.Difficulty;
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
 * </ul>
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class CommandGameTests {

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
}
