package com.dwinovo.numen.pathing.gametest;

import net.minecraft.world.entity.EntitySpawnReason;
import com.dwinovo.numen.api.gametest.Checks;
import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;

/**
 * 动态世界:被推离路线之后回到路上;目标在挪(跟随)。走到一半前方的格被改了,见 {@code RecheckGameTests}。
 * 用例中途改世界走 {@link Trial#change},不算进导航的账。
 */
public class DynamicGameTests {

    private static final String BATCH = "pathing_dynamic";

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    private static double relX(Trial t, Trial.Run r) {
        return r.body.getX() - t.origin.getX();
    }

    private static double relZ(Trial t, Trial.Run r) {
        return r.body.getZ() - t.origin.getZ();
    }

    /** 走在一条直路上,半路被横着推开两格:回到原来那条路上(十格之内回到那一排),接着走到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 504)
    public static void returns_to_the_route_after_a_push(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        double[] pushedAt = {Double.NaN};
        t.go(body, Goals.at(t.at(30, 1, 5)), RouteSpec.defaults()).within(500)
                .during(r -> {
                    double x = relX(t, r);
                    if (Double.isNaN(pushedAt[0]) && x >= 12) {
                        pushedAt[0] = x;
                        r.body.setDeltaMovement(r.body.getDeltaMovement().add(0, 0.2, 0.9));
                        r.body.hurtMarked = true;
                    } else if (!Double.isNaN(pushedAt[0]) && x > pushedAt[0] + 10 && x < 29
                            && Math.floor(relZ(t, r)) != 5) {
                        throw Checks.failure("被推开之后十格还没回到原来那一排:z = " + relZ(t, r));
                    }
                })
                .arrives();
    }

    /**
     * 一头猪沿直线慢慢往前挪,宿主看它挪出两格就把目标换成"它附近两格半以内":跟着走,途中换了几次目标,最后停在它身边。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 576)
    public static void follows_a_moving_target(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        Pig pig = EntityType.PIG.create(t.level, EntitySpawnReason.STRUCTURE);
        pig.setNoAi(true);
        BlockPos from = t.at(12, 1, 20);
        pig.snapTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0, 0);
        t.level.addFreshEntity(pig);
        TestBody body = t.body(4, 1, 5);
        BlockPos[] aim = {from};
        int[] retargets = {0};
        t.go(body, Goals.within(Goals.at(from), 0, 2.5), RouteSpec.defaults()).within(566)
                .during(r -> {
                    if (r.ticks % 5 == 0 && pig.getX() - t.origin.getX() < 34) {
                        pig.snapTo(pig.getX() + 1, pig.getY(), pig.getZ(), 0, 0);
                    }
                    BlockPos now = pig.blockPosition();
                    if (now.distSqr(aim[0]) >= 4) {
                        aim[0] = now;
                        retargets[0]++;
                        r.navigation.retarget(Goals.within(Goals.at(now), 0, 2.5));
                    }
                })
                .arrives().then(r -> {
                    pig.discard();
                    if (retargets[0] < 3) {
                        throw Checks.failure("目标只换了 " + retargets[0] + " 次,场景没起作用");
                    }
                    if (r.body.blockPosition().distSqr(aim[0]) > 2.5 * 2.5) {
                        throw Checks.failure("没停在最后那个目标附近:" + t.rel(r.body.blockPosition())
                                + " 目标 " + t.rel(aim[0]));
                    }
                });
    }
}
