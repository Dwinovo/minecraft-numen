package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;

/**
 * 创造飞行的寻路:创造模式的身体飞越走不过去的峡谷到对岸点降落,飞到半空一格停在那里,飞上高墙落在顶上,近处不起飞(价钱定);
 * 生存模式的身体面对同一道峡谷没有路,行为不变。用普通假玩家({@link TestBody})经 {@code Navigator} 真走,看实际账里的起降与飞行距离。
 */
public class FlightGameTests {

    private static final String BATCH = "pathing_flight";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 场地正中一道十二格宽的峡谷:x 从 10 到 21 没有地板。 */
    private static Trial canyon(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(10, 0, 0, 21, 0, 39, Blocks.AIR);
        return t;
    }

    private static TestBody creative(Trial t, int x, int y, int z) {
        TestBody body = t.body(x, y, z);
        body.setGameMode(GameType.CREATIVE);
        return body;
    }

    /** 创造模式:从这岸起飞,飞过峡谷,落在对岸的目标点上;落地之后不再飞,账上一次起飞、一次降落、飞了十几格。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void a_creative_body_flies_across_a_canyon_and_lands_on_the_far_side(GameTestHelper helper) {
        Trial t = canyon(helper);
        TestBody body = creative(t, 5, 1, 20);
        BlockPos far = t.at(28, 1, 20);
        t.go(body, Goals.at(far), RouteSpec.defaults())
                .during(r -> {
                    if (r.body.getY() < t.origin.getY() - 0.5) {
                        throw new GameTestAssertException("掉进了峡谷:脚在 " + (r.body.getY() - t.origin.getY()));
                    }
                })
                .then(r -> {
                    if (!r.body.onGround() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("到了对岸却没落地:onGround=" + r.body.onGround() + " flying=" + r.body.getAbilities().flying);
                    }
                    var ledger = r.report.ledger();
                    if (ledger.launches().size() != 1 || ledger.landings().size() != 1) {
                        throw new GameTestAssertException("账上该有一次起飞一次降落,实际起飞 " + ledger.launches() + " 降落 " + ledger.landings());
                    }
                    if (ledger.flown() < 14) {
                        throw new GameTestAssertException("飞过峡谷至少十几格,账上只飞了 " + ledger.flown());
                    }
                })
                .arrives();
    }

    /** 创造模式:目标是半空的一格(离地七格),飞到那里停住;到了还在飞,脚停在那一格里,键松开之后也不漂走。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void a_creative_body_flies_to_a_cell_in_mid_air_and_hovers_there(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = creative(t, 5, 1, 20);
        BlockPos air = t.at(20, 8, 20);
        t.go(body, Goals.at(air), RouteSpec.defaults())
                .then(r -> {
                    if (!r.body.getAbilities().flying) {
                        throw new GameTestAssertException("半空的目标,到了应当还在飞");
                    }
                    if (!r.body.blockPosition().equals(air)) {
                        throw new GameTestAssertException("该停在 " + t.rel(air) + ",停在 " + t.rel(r.body.blockPosition()));
                    }
                    if (r.report.ledger().landings().size() != 0) {
                        throw new GameTestAssertException("半空的目标不该降落过:" + r.report.ledger().landings());
                    }
                })
                .arrives();
    }

    /** 创造模式:墙高十格,翻不过去,飞上去落在墙顶上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void a_creative_body_flies_over_a_tall_wall_and_lands_on_top(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(12, 1, 0, 13, 10, 39, Blocks.STONE);
        TestBody body = creative(t, 5, 1, 20);
        BlockPos top = t.at(13, 11, 20);
        t.go(body, Goals.at(top), RouteSpec.defaults())
                .then(r -> {
                    if (!r.body.onGround() || r.body.getAbilities().flying) {
                        throw new GameTestAssertException("该落在墙顶上:onGround=" + r.body.onGround() + " flying=" + r.body.getAbilities().flying);
                    }
                })
                .arrives();
    }

    /** 创造模式、平地、六格以内:走过去,不起飞(起飞与刹车的价钱不值得);一路没飞过。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void a_short_trip_on_flat_ground_is_walked_not_flown(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = creative(t, 5, 1, 20);
        t.go(body, Goals.at(t.at(11, 1, 20)), RouteSpec.defaults())
                .during(r -> {
                    if (r.body.getAbilities().flying) {
                        throw new GameTestAssertException("近处该走过去,却飞起来了");
                    }
                })
                .then(r -> {
                    if (r.report.ledger().launches().size() != 0 || r.report.ledger().flown() != 0) {
                        throw new GameTestAssertException("账上不该有飞行:" + r.report.ledger().launches());
                    }
                })
                .arrives();
    }

    /** 生存模式面对同一道峡谷:不会飞,要过去只能搭桥(规格不许改地形),结局是要改地形才行;身体一直在地上。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 400)
    public static void a_survival_body_cannot_cross_the_canyon_without_changing_the_world(GameTestHelper helper) {
        Trial t = canyon(helper);
        TestBody body = t.body(5, 1, 20);
        t.go(body, Goals.at(t.at(28, 1, 20)), RouteSpec.defaults()).fails(Outcome.NeedsChanges.class)
                .then(r -> {
                    if (r.body.getAbilities().flying || r.report.ledger().flown() != 0) {
                        throw new GameTestAssertException("生存模式的身体不会飞");
                    }
                });
    }
}
