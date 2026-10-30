package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 规划与执行一直对不上时,一趟在有限时间内照实收场,不无限重试。规划只读方块,看不见实体:闭着壳的潜影贝是一堵规划看不见、
 * 身体撞得上的墙(原版里玩家撞着它走不过去,既没有碰撞报错,也没有任何一步报"做不到")。
 *
 * <p>另有三种硬挡住的路线:路真的不通,一趟照实收场、说对原因(禁区挡着、垫路料不够、没有镐挖不动)。它们量的是收场原因,与地形无关,
 * 在空场地里搭一间封死的小廊子。
 *
 * <p>走法是:执行里一步迟迟做不完,看护({@code Watchdog})按期限判它卡住,这一步在这一趟的记忆里记为不通、只重搜当前这一段、不再走它,
 * 整趟的总预算用完照实收场。
 */
public class TerminationGameTests {

    private static final String BATCH = "pathing_termination";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 在 {@code (x, y, z)} 这一格里放一只不动的潜影贝:一格见方的实心挡板。 */
    private static void shulker(Trial t, int x, int y, int z) {
        Shulker shulker = EntityType.SHULKER.create(t.level);
        BlockPos at = t.at(x, y, z);
        shulker.setNoAi(true);
        shulker.setInvulnerable(true);
        shulker.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
        t.level.addFreshEntity(shulker);
    }

    /**
     * 一条一格宽的走廊,中间被一只潜影贝堵死:身体走到它跟前顶着不动,看护判卡住,这一步记为不通;走廊里没有别的路,搜完了说受阻,
     * 在哪一步、哪一格,没有无限地顶下去。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 447)
    public static void a_corridor_blocked_by_what_planning_cannot_see_ends_blocked(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 4, 20, 3, 4, Blocks.STONE);
        t.fill(0, 1, 6, 20, 3, 6, Blocks.STONE);
        t.fill(0, 4, 4, 20, 4, 6, Blocks.STONE);
        shulker(t, 10, 1, 5);
        TestBody body = t.body(3, 1, 5);
        t.go(body, Goals.at(t.at(17, 1, 5)), RouteSpec.defaults()).within(600)
                .fails(Outcome.Blocked.class, blocked -> {
                    if (blocked.blockage().hitch() == null) {
                        throw new GameTestAssertException("该是执行里卡住,却是前提不成立:" + blocked.blockage());
                    }
                })
                .then(r -> {
                    if (r.report.journal().replans(Journal.Replan.TIMEOUT) < 1) {
                        throw new GameTestAssertException("没有被看护判成卡住:" + r.report.journal().replans());
                    }
                    if (r.report.journal().blockages() < 1) {
                        throw new GameTestAssertException("卡住的这一步没有记为不通:" + r.report.journal().blockages());
                    }
                });
    }

    /**
     * 一道横贯场地的潜影贝墙(十二只,墙两头接着石墙),哪里都过不去:每试一处,顶着卡住、记为不通、换一处再试;十二处试完
     * (或总预算先用完)就收场。试的次数不超过墙上的格数,时间有界,收场不是"到了"。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 3500)
    public static void a_wall_nobody_can_cross_is_tried_a_bounded_number_of_times(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 1, 0, 10, 3, 0, Blocks.STONE);
        t.fill(10, 1, 13, 10, 3, 13, Blocks.STONE);
        t.fill(0, 1, 0, 39, 3, 0, Blocks.STONE);
        t.fill(0, 1, 13, 39, 3, 13, Blocks.STONE);
        for (int z = 1; z <= 12; z++) {
            shulker(t, 10, 1, z);
        }
        TestBody body = t.body(3, 1, 6);
        t.go(body, Goals.at(t.at(20, 1, 6)), RouteSpec.defaults()).within(3000)
                .fails(Outcome.class, outcome -> {
                    if (!(outcome instanceof Outcome.Blocked) && !(outcome instanceof Outcome.OutOfBudget)
                            && !(outcome instanceof Outcome.NoRoute)) {
                        throw new GameTestAssertException("该照实说过不去,却是 " + outcome);
                    }
                })
                .then(r -> {
                    int stuck = r.report.journal().replans(Journal.Replan.TIMEOUT);
                    if (stuck < 1 || stuck > 12 + 2) {
                        throw new GameTestAssertException("卡住的次数 " + stuck + ",墙上只有十二格");
                    }
                });
    }

    private static final RouteSpec CHANGES = RouteSpec.defaults().edit().changes(true).build();

    /** 一条封死的廊子:{@code x0..x1} 长、{@code z = 4..8} 宽五格、高三格,地、两侧墙与顶都是基岩——挖不动,也没处可垫。 */
    private static void sealedCorridor(Trial t, int x0, int x1) {
        t.fill(x0, 0, 3, x1, 0, 9, Blocks.BEDROCK);
        t.fill(x0, 1, 3, x1, 4, 3, Blocks.BEDROCK);
        t.fill(x0, 1, 9, x1, 4, 9, Blocks.BEDROCK);
        t.fill(x0, 4, 4, x1, 4, 8, Blocks.BEDROCK);
        t.fill(x0 - 1, 0, 3, x0 - 1, 4, 9, Blocks.BEDROCK);
        t.fill(x1 + 1, 0, 3, x1 + 1, 4, 9, Blocks.BEDROCK);
    }

    /**
     * 禁区挡在中间:三格厚的石墙隔断整条廊子,墙所在的 x 范围整片(从底到顶、整个廊子)是许可不让挖放的禁区——绕不过去,
     * 结局是"许可拒绝",点出墙上的一格。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void a_forbidden_zone_across_the_corridor_ends_denied(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        sealedCorridor(t, 1, 25);
        t.fill(10, 1, 4, 12, 3, 8, Blocks.STONE);
        t.terrain = (change, pos, state, view) -> {
            int x = pos.getX() - t.origin.getX();
            return x >= 8 && x <= 14 ? Permit.deny("forbidden zone") : Permit.ALLOW;
        };
        TestBody body = t.body(2, 1, 6);
        t.go(body, Goals.at(t.at(24, 1, 6)), CHANGES).within(600)
                .fails(Outcome.Denied.class, denied -> {
                    int x = denied.cell().getX() - t.origin.getX();
                    if (x < 10 || x > 12) {
                        throw new GameTestAssertException("该点出墙上的一格(x 10..12),却是 " + t.rel(denied.cell()));
                    }
                });
    }

    /**
     * 方块不够过沟:六格宽的沟(两边是四格高的基岩台子,沟里落下去摔不起),身上只有三块圆石——搭了三块用完,结局是"缺料",
     * 说出这条路还要放几块。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void a_gap_wider_than_the_blocks_carried_ends_without_materials(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 13, 4, 39, Blocks.BEDROCK);
        t.fill(20, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(10, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 3));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(24, 5, 5)), CHANGES).within(800)
                .fails(Outcome.NoMaterials.class, n -> {
                    if (n.needed() <= 0) {
                        throw new GameTestAssertException("该说还缺几块,却是 " + n.needed());
                    }
                });
    }

    /**
     * 没有镐挖不动黑曜石:封死的廊子(地、两侧墙、顶与两头是基岩,垫路料也没处可垫)里横着两格厚的黑曜石墙,空手——结局是"没有路",
     * 原因是挖不动。廊子封死,是为了让"有料能不能绕过去"的诊断也答不出路,留下的原因只有黑曜石。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void obsidian_without_a_pickaxe_ends_with_no_route(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        sealedCorridor(t, 1, 25);
        t.fill(10, 1, 4, 11, 3, 8, Blocks.OBSIDIAN);
        TestBody body = t.body(2, 1, 6);
        t.go(body, Goals.at(t.at(24, 1, 6)), CHANGES).within(600)
                .fails(Outcome.NoRoute.class, n -> {
                    if (n.cause() == null || n.cause().reason() != Reason.TOO_HARD) {
                        throw new GameTestAssertException("该是挖不动,却是 " + n.cause());
                    }
                });
    }
}
