package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.drive.Journal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 规划与执行一直对不上时,一趟在有限时间内照实收场,不无限重试。规划只读方块,看不见实体:闭着壳的潜影贝是一堵规划看不见、
 * 身体撞得上的墙(原版里玩家撞着它走不过去,既没有碰撞报错,也没有任何一步报"做不到")。
 *
 * <p>另有一种硬挡住的路线:沟比身上的垫路料能搭的宽,路真的不通,一趟照实收场、说对原因。
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
        Shulker shulker = EntityTypes.SHULKER.create(t.level, net.minecraft.world.entity.EntitySpawnReason.STRUCTURE);
        BlockPos at = t.at(x, y, z);
        shulker.setNoAi(true);
        shulker.setPermanentlyInvulnerable(true);
        shulker.snapTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0, 0);
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
        t.go(body, Goals.at(t.at(17, 1, 5)), RouteSpec.defaults()).within(437)
                .fails(Outcome.Blocked.class, blocked -> {
                    if (blocked.blockage().hitch() == null) {
                        throw Checks.failure("该是执行里卡住,却是前提不成立:" + blocked.blockage());
                    }
                })
                .then(r -> {
                    if (r.report.journal().replans(Journal.Replan.TIMEOUT) < 1) {
                        throw Checks.failure("没有被看护判成卡住:" + r.report.journal().replans());
                    }
                    if (r.report.journal().blockages() < 1) {
                        throw Checks.failure("卡住的这一步没有记为不通:" + r.report.journal().blockages());
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
                        throw Checks.failure("该照实说过不去,却是 " + outcome);
                    }
                })
                .then(r -> {
                    int stuck = r.report.journal().replans(Journal.Replan.TIMEOUT);
                    if (stuck < 1 || stuck > 12 + 2) {
                        throw Checks.failure("卡住的次数 " + stuck + ",墙上只有十二格");
                    }
                });
    }

    private static final RouteSpec CHANGES = RouteSpec.defaults().edit().changes(true).build();

    /**
     * 方块不够过沟:六格宽的沟(两边是四格高的基岩台子,沟里落下去摔不起),身上只有三块圆石——搭了三块用完,结局是"缺料",
     * 说出这条路还要放几块。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 350)
    public static void a_gap_wider_than_the_blocks_carried_ends_without_materials(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(0, 1, 0, 13, 4, 39, Blocks.BEDROCK);
        t.fill(20, 1, 0, 39, 4, 39, Blocks.BEDROCK);
        TestBody body = t.body(10, 5, 5);
        Trial.give(body, new ItemStack(Items.COBBLESTONE, 3));
        t.materials = Trial.carried(body, Blocks.COBBLESTONE);
        t.go(body, Goals.at(t.at(24, 5, 5)), CHANGES).within(340)
                .fails(Outcome.NoMaterials.class, n -> {
                    if (n.needed() <= 0) {
                        throw Checks.failure("该说还缺几块,却是 " + n.needed());
                    }
                });
    }
}
