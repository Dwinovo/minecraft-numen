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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * {@code numen mc}:以她的身份执行游戏指令。能用哪些是服务器按她的权限等级定的(测试里直接把她记进 OP 表,
 * 等级 2);执行前过权限层,没有规则就问主人,主人的允许与拒绝规则直接生效;指令的回显原样回到回执;
 * {@code --help} 只列服务器让她用的指令,分页。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class McCommandGameTests {

    /** 指令批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_mc")
    public static void prepareMcBatch(ServerLevel level) {
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

    /** 帮助当场回执里给模型读的那段话。 */
    private static String help(NumenPlayer companion, String line) {
        ToolRun run = command(companion, line);
        if (!run.succeeded()) {
            throw new net.minecraft.gametest.framework.GameTestAssertException(line + " failed: " + run.reply());
        }
        return com.google.gson.JsonParser.parseString(run.reply()).getAsJsonObject().get("message").getAsString();
    }

    private static String setblock(GameTestHelper helper, BlockPos rel) {
        BlockPos at = helper.absolutePos(rel);
        return "setblock " + at.getX() + " " + at.getY() + " " + at.getZ() + " minecraft:stone";
    }

    /** 服务器不让她用:没有 OP 时 give 当场如实失败,说清是服务器不让;不问主人,背包不变。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mc")
    public static void mc_without_op_is_refused_by_the_server(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_guest", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_host");
        ToolRun give = command(companion, "numen mc /give @s minecraft:diamond");

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
     * 有 OP、主人允许 give:不问、直接执行,背包里多了钻石,回执里是服务器的原话。参数写错的当场失败,附上这条的用法。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mc")
    public static void mc_give_with_op_and_an_allow_rule_runs_and_echoes(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_op", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_admin");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.ALLOW, Rule.parse("command(give)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun give = command(companion, "numen mc give @s minecraft:diamond 2");
        ToolRun typo = command(companion, "numen mc give @s minecraft:not_an_item");

        helper.succeedWhen(() -> {
            helper.assertTrue(give.done(), "give has not finished");
            helper.assertTrue(give.succeeded(), "give failed: " + give.outcome());
            helper.assertTrue(give.task().getToolName().equals("mc"),
                    "the task is not named after the command group: " + give.task().getToolName());
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
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_mc")
    public static void mc_setblock_without_a_rule_asks_the_owner_first(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_builder", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_landlord");
        grantOp(companion);
        String line = setblock(helper, target);
        ToolRun run = command(companion, "numen mc " + line);
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

    /** 主人写了拒绝 setblock 的规则:当场如实失败、理由是那一行,不弹卡,世界不变。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mc")
    public static void mc_setblock_denied_by_a_rule_fails_with_the_rule(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = new BlockPos(8, 3, 8);
        NumenPlayer companion = spawnAt(helper, "gametest_mc_forbidden", new BlockPos(4, 2, 4), false);
        NumenPlayer owner = presentOwner(helper, companion, "gametest_mc_strict");
        grantOp(companion);
        storeOf(owner).add(Verdict.Kind.DENY, Rule.parse("command(setblock)"));
        boolean[] asked = new boolean[1];
        helper.onEachTick(() -> asked[0] |= ConsentDesk.of(companion).pending() != null);
        ToolRun run = command(companion, "numen mc " + setblock(helper, target));

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
     * {@code numen mc --help} 只列服务器让她用的:没有 OP 时有 msg、没有 give;有 OP 后列表长到要翻页,give 在其中一页,
     * 每页都说还剩几条、怎么翻。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_mc")
    public static void mc_help_lists_only_what_the_server_lets_her_run(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_mc_reader", new BlockPos(4, 2, 4), false);
        String guest = help(companion, "numen mc --help");
        helper.assertTrue(guest != null && guest.contains("Commands the server lets you run now:")
                        && guest.contains("/msg <targets> <message>") && !guest.contains("/give "),
                "the no-op help lists the wrong commands: " + guest);

        grantOp(companion);
        String first = help(companion, "numen mc --help");
        Matcher pages = Pattern.compile("\\(page 1 of (\\d+), \\d+ more: numen mc --help --page 2\\)").matcher(first);
        helper.assertTrue(pages.find(), "the op help is not paged: " + first);
        boolean listsGive = first.contains("/give <targets> <item> [<count>]");
        for (int page = 2; page <= Integer.parseInt(pages.group(1)); page++) {
            String next = help(companion, "numen mc --help --page " + page);
            helper.assertTrue(next.startsWith("numen mc <command...>\n"), "page " + page + " lost its head: " + next);
            listsGive |= next.contains("/give <targets> <item> [<count>]");
        }
        helper.assertTrue(listsGive, "the op help never lists /give");
        cleanUp(helper, companion, null);
        helper.succeed();
    }
}
