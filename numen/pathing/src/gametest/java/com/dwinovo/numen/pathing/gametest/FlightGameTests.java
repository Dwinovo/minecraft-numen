package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;
import static com.dwinovo.numen.pathing.gametest.Trial.LONG;
import static com.dwinovo.numen.pathing.gametest.Trial.TALL;

import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Kinematics;
import com.dwinovo.numen.pathing.world.Kinematics.Keys;
import com.dwinovo.numen.pathing.world.Kinematics.Motion;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

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
        t.fly(body, Goals.at(far), RouteSpec.defaults())
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
        t.fly(body, Goals.at(air), RouteSpec.defaults())
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
        t.fly(body, Goals.at(top), RouteSpec.defaults())
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
        t.fly(body, Goals.at(t.at(11, 1, 20)), RouteSpec.defaults())
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

    // ==================== 飞得有多快:对着步进器算出的理想耗时 ====================

    /** 实际耗时的上限相对理想耗时的倍数,另加起飞与收场的余量(刻)。 */
    private static final double SLACK = 1.3;
    private static final int EXTRA = 25;

    /** 飞着从静止升 {@code rise} 格停在那里,最快要几刻:按住跳到再松开也滑得到为止,整数刻,逐刻走步进器。 */
    private static int climbTicks(float speed, double rise) {
        Motion m = new Motion(Vec3.ZERO, Vec3.ZERO);
        for (int t = 1; t < 2000; t++) {
            double left = rise - m.pos().y;
            boolean up = Kinematics.flyRise(speed, m.velocity().y, 1) <= left;
            m = Kinematics.fly(m, new Keys(0, 0, up, false, false, 0, 0, false), speed);
            if (rise - m.pos().y < 0.4 && Math.abs(m.velocity().y) < 0.02) {
                return t;
            }
        }
        throw new IllegalStateException("步进器里升不到 " + rise);
    }

    /** 飞着从静止水平飞 {@code distance} 格停在那里,最快要几刻:疾飞到松开滑得到为止,再滑停。 */
    private static int cruiseTicks(float speed, double distance) {
        Motion m = new Motion(Vec3.ZERO, Vec3.ZERO);
        for (int t = 1; t < 2000; t++) {
            Keys sprint = new Keys(1, 0, false, false, true, 0, 0, false);
            boolean push = Kinematics.flyRest(m, sprint, speed).z() <= distance;
            m = Kinematics.fly(m, push ? sprint : Keys.idle(0, 0), speed);
            if (distance - m.pos().z < 1.2 && m.velocity().lengthSqr() < 0.05 * 0.05) {
                return t;
            }
        }
        throw new IllegalStateException("步进器里飞不到 " + distance);
    }

    /** 一趟飞行实际的耗时不超过理想耗时(水平与竖直同时进行取长的,加上连按两下跳起飞)的倍数加余量;不重搜,到点悬停或落地。 */
    private static void fliesNearTheIdeal(Trial.Run run, int ideal, boolean hovers) {
        int limit = (int) (ideal * SLACK) + EXTRA;
        run.within(limit).then(r -> {
            if (r.report.journal().blockages() != 0) {
                throw new GameTestAssertException("飞行不该有走不下去的步:" + r.report.journal().replans());
            }
            if (r.body.getAbilities().flying != hovers) {
                throw new GameTestAssertException(hovers ? "该悬停在目标上,却落了地" : "该落在地上,却还在飞");
            }
            if (r.ticks > limit) {
                throw new GameTestAssertException("用了 " + r.ticks + " 刻,理想 " + ideal + " 刻,上限 " + limit);
            }
        }).arrives();
    }

    /** 直升四十格到半空一格悬停:每格不刹,一路按着跳,到近处才松开。 */
    @GameTest(template = TALL, batch = BATCH, timeoutTicks = 900)
    public static void a_creative_body_climbs_forty_blocks_in_close_to_the_ideal_time(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = creative(t, 8, 1, 8);
        int ideal = Kinematics.TAKEOFF_TAPS + climbTicks(body.getAbilities().getFlyingSpeed(), 40);
        fliesNearTheIdeal(t.fly(body, Goals.at(t.at(8, 41, 8)), RouteSpec.defaults()), ideal, true);
    }

    /** 平飞六十四格过一道深沟,落在对岸:疾飞一路,近了松开滑过去再落地。 */
    @GameTest(template = LONG, batch = BATCH, timeoutTicks = 900)
    public static void a_creative_body_cruises_sixty_four_blocks_in_close_to_the_ideal_time(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(6, 0, 0, 68, 0, 23, Blocks.AIR);
        TestBody body = creative(t, 5, 1, 12);
        int ideal = Kinematics.TAKEOFF_TAPS + cruiseTicks(body.getAbilities().getFlyingSpeed(), 64);
        fliesNearTheIdeal(t.fly(body, Goals.at(t.at(69, 1, 12)), RouteSpec.defaults()), ideal, false);
    }

    /** 斜着爬升:水平十二格、竖直二十四格,水平与竖直同时进行,慢的是竖直。脚下只有一小块地,不起飞没有路。 */
    @GameTest(template = TALL, batch = BATCH, timeoutTicks = 900)
    public static void a_creative_body_climbs_diagonally_in_close_to_the_ideal_time(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(4, 0, 0, 15, 0, 15, Blocks.AIR);
        t.fill(0, 0, 4, 3, 0, 15, Blocks.AIR);
        TestBody body = creative(t, 2, 1, 2);
        float speed = body.getAbilities().getFlyingSpeed();
        int ideal = Kinematics.TAKEOFF_TAPS + Math.max(climbTicks(speed, 24), cruiseTicks(speed, Math.sqrt(2) * 12));
        fliesNearTheIdeal(t.fly(body, Goals.at(t.at(14, 25, 14)), RouteSpec.defaults()), ideal, true);
    }
}
