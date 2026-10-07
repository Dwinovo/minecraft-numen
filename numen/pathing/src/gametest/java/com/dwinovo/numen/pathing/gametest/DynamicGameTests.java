package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import com.dwinovo.numen.pathing.drive.EditLedger;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * 动态世界:走到一半前方被放了方块、门被关上;被推离路线、被打飞之后回到路上;重搜不无谓换道;目标在挪(跟随)。
 * 用例中途改世界走 {@link Trial#change},不算进导航的账。
 */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
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

    /** 走到一半,前方横着砌起一道墙,只在远处留口:重新规划,从口里绕过去。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 800)
    public static void replans_when_a_wall_goes_up_ahead(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        boolean[] built = {false};
        t.go(body, Goals.at(t.at(30, 1, 5)), RouteSpec.defaults()).within(700)
                .during(r -> {
                    if (!built[0] && relX(t, r) >= 10) {
                        built[0] = true;
                        for (int z = 0; z <= 39; z++) {
                            for (int y = 1; y <= 3 && z != 15; y++) {
                                t.change(16, y, z, Blocks.STONE.defaultBlockState());
                            }
                        }
                    }
                })
                .arrives().then(Scenes::unaltered);
    }

    /** 唯一的出口是一扇开着的门,走到一半门被关上:走到门前自己打开,实际账里记下这一开。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void reopens_a_door_closed_on_the_way(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        t.fill(12, 1, 0, 12, 3, 39, Blocks.STONE);
        Scenes.door(t, 12, 1, 5, Blocks.OAK_DOOR, Direction.EAST, true);
        TestBody body = t.body(4, 1, 5);
        boolean[] closed = {false};
        t.go(body, Goals.at(t.at(20, 1, 5)), RouteSpec.defaults()).within(500)
                .during(r -> {
                    if (!closed[0] && relX(t, r) >= 9) {
                        closed[0] = true;
                        for (int y = 1; y <= 2; y++) {
                            BlockState half = t.state(12, y, 5);
                            t.change(12, y, 5, half.setValue(DoorBlock.OPEN, false));
                        }
                    }
                })
                .arrives().then(r -> {
                    boolean toggled = r.report.ledger().entries().stream().anyMatch(e -> e instanceof EditLedger.Toggled);
                    if (!toggled) {
                        throw new GameTestAssertException("门关上之后没自己开:" + r.report.ledger().entries());
                    }
                });
    }

    /** 走在一条直路上,半路被横着推开两格:回到原来那条路上(十格之内回到那一排),接着走到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
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
                        throw new GameTestAssertException("被推开之后十格还没回到原来那一排:z = " + relZ(t, r));
                    }
                })
                .arrives();
    }

    /**
     * 两条一样长、两格宽的走廊,走进其中一条之后,前方同一排被放了一块石头:在这条走廊里侧身绕过去,不退出来换另一条。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void keeps_its_lane_when_replanning(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        for (int z : new int[] {3, 6, 7, 10}) {
            t.fill(8, 1, z, 26, 3, z, Blocks.STONE);
        }
        TestBody body = t.body(4, 1, 7);
        int[] lane = {-1};
        t.go(body, Goals.at(t.at(30, 1, 7)), RouteSpec.defaults()).within(600)
                .during(r -> {
                    double x = relX(t, r);
                    int z = (int) Math.floor(relZ(t, r));
                    if (lane[0] < 0 && x >= 14) {
                        lane[0] = z <= 5 ? 0 : 1;
                        t.change((int) Math.floor(x) + 3, 1, z, Blocks.STONE.defaultBlockState());
                    } else if (lane[0] >= 0 && x >= 9 && x < 26 && (z <= 5 ? 0 : 1) != lane[0]) {
                        throw new GameTestAssertException("重搜之后换了走廊:z = " + relZ(t, r));
                    }
                })
                .arrives();
    }

    /**
     * 一头猪沿直线慢慢往前挪,宿主看它挪出两格就把目标换成"它附近两格半以内":跟着走,途中换了几次目标,最后停在它身边。
     */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 700)
    public static void follows_a_moving_target(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        Pig pig = EntityType.PIG.create(t.level);
        pig.setNoAi(true);
        BlockPos from = t.at(12, 1, 20);
        pig.moveTo(from.getX() + 0.5, from.getY(), from.getZ() + 0.5, 0, 0);
        t.level.addFreshEntity(pig);
        TestBody body = t.body(4, 1, 5);
        BlockPos[] aim = {from};
        int[] retargets = {0};
        t.go(body, Goals.within(Goals.at(from), 0, 2.5), RouteSpec.defaults()).within(600)
                .during(r -> {
                    if (r.ticks % 5 == 0 && pig.getX() - t.origin.getX() < 34) {
                        pig.moveTo(pig.getX() + 1, pig.getY(), pig.getZ(), 0, 0);
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
                        throw new GameTestAssertException("目标只换了 " + retargets[0] + " 次,场景没起作用");
                    }
                    if (r.body.blockPosition().distSqr(aim[0]) > 2.5 * 2.5) {
                        throw new GameTestAssertException("没停在最后那个目标附近:" + t.rel(r.body.blockPosition())
                                + " 目标 " + t.rel(aim[0]));
                    }
                });
    }

    /** 走在路上被打飞(横着带往上的一下):落地之后回到路上,接着走到。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = 600)
    public static void recovers_after_being_knocked_flying(GameTestHelper helper) {
        Trial t = new Trial(helper).floor();
        TestBody body = t.body(4, 1, 5);
        boolean[] hit = {false};
        t.go(body, Goals.at(t.at(30, 1, 5)), RouteSpec.defaults()).within(500)
                .during(r -> {
                    if (!hit[0] && relX(t, r) >= 12) {
                        hit[0] = true;
                        r.body.setDeltaMovement(new Vec3(-0.9, 0.6, 0.9));
                        r.body.hurtMarked = true;
                    }
                })
                .arrives();
    }
}
