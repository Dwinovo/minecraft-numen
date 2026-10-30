package com.dwinovo.numen.pathing.drive;

import static com.dwinovo.numen.pathing.Vanilla.SURVIVAL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** 到达:停稳之后到没到只看目标自己的判定,要看的那一格从眼睛看不看得见;不经 {@link Driver}。 */
class ArrivalTest {

    private static final int Y = 64;

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static TestWorld flat() {
        return new TestWorld().floor(-4, -4, 20, 4, Y - 1);
    }

    private static Vec3 eye(BlockPos node) {
        return SURVIVAL.eye(node.getX(), node.getY(), node.getZ());
    }

    @Test
    void standingInTheGoalIsArrivingWithNothingToLookAt() {
        BlockPos goalCell = new BlockPos(5, Y, 0);
        Arrival.Verdict verdict = Arrival.judge(Goals.at(goalCell), flat(), SURVIVAL, goalCell, eye(goalCell), 4.5);
        assertEquals(new Arrival.Verdict.Arrived(null), verdict);
    }

    @Test
    void standingOutsideTheGoalReplans() {
        BlockPos goalCell = new BlockPos(5, Y, 0);
        BlockPos here = new BlockPos(4, Y, 0);
        Arrival.Verdict verdict = Arrival.judge(Goals.at(goalCell), flat(), SURVIVAL, here, eye(here), 4.5);
        assertInstanceOf(Arrival.Verdict.Replan.class, verdict);
    }

    /** 平地上一个熔炉,站在它东边挨着的那一格,目标是"用它":规划时这个站位看得见东面。 */
    private static TestWorld furnaceWorld(BlockPos furnace) {
        return flat().set(furnace, Blocks.FURNACE.defaultBlockState());
    }

    @Test
    void aGoalThatWantsASightedBlockAimsAtItWhenSeen() {
        BlockPos furnace = new BlockPos(0, Y, 0);
        TestWorld world = furnaceWorld(furnace);
        Goal use = Goals.use(world, SURVIVAL, furnace);
        BlockPos here = new BlockPos(1, Y, 0);
        Arrival.Verdict.Arrived arrived = assertInstanceOf(Arrival.Verdict.Arrived.class,
                Arrival.judge(use, world, SURVIVAL, here, eye(here), SURVIVAL.blockReach()));
        assertNotNull(arrived.aim(), "要看着那一格");
    }

    @Test
    void aBlockOutOfTheEyesReachIsNotArrival() {
        BlockPos furnace = new BlockPos(0, Y, 0);
        TestWorld world = furnaceWorld(furnace);
        Goal use = Goals.use(world, SURVIVAL, furnace);
        BlockPos here = new BlockPos(1, Y, 0);
        // 站位在东面,可眼睛此刻已经离得够不着(身子被推走了):复核按眼睛此刻的位置,看不见就不算到
        Vec3 elsewhere = new Vec3(30.5, Y + 1.62, 0.5);
        assertEquals(new Arrival.Verdict.Blind(furnace),
                Arrival.judge(use, world, SURVIVAL, here, elsewhere, SURVIVAL.blockReach()));
    }

    /**
     * 工作台摆在一块石头上,只有西面敞开;列好站位之后,正对西面的那一格(半空,不是站位)被放上一块玻璃:走到了搜索挑的站位,在世界上复核时
     * 看不见它,不报到达,是"看不见"。
     */
    @Test
    void sightBlockedAfterPlanningIsNotArrival() {
        TestWorld world = new TestWorld().floor(-4, -4, 20, 8, Y - 1);
        BlockPos table = new BlockPos(10, Y + 1, 5);
        world.set(new BlockPos(10, Y, 5), Blocks.STONE.defaultBlockState()).set(table, Blocks.CRAFTING_TABLE.defaultBlockState());
        for (BlockPos glass : new BlockPos[] {new BlockPos(10, Y + 1, 4), new BlockPos(10, Y + 1, 6), new BlockPos(11, Y + 1, 5),
                new BlockPos(10, Y + 2, 5)}) {
            world.set(glass, Blocks.GLASS.defaultBlockState());
        }
        Goal use = Goals.use(world, SURVIVAL, table);
        BlockPos here = new BlockPos(8, Y, 5);
        assertInstanceOf(Arrival.Verdict.Arrived.class, Arrival.judge(use, world, SURVIVAL, here, eye(here), SURVIVAL.blockReach()));
        world.set(new BlockPos(9, Y + 1, 5), Blocks.GLASS.defaultBlockState());
        assertEquals(new Arrival.Verdict.Blind(table), Arrival.judge(use, world, SURVIVAL, here, eye(here), SURVIVAL.blockReach()));
    }

    @Test
    void passingThroughIsJustBeingInsideTheGoal() {
        BlockPos goalCell = new BlockPos(5, Y, 0);
        assertTrue(Arrival.passes(Goals.at(goalCell), flat(), SURVIVAL, goalCell));
        assertFalse(Arrival.passes(Goals.at(goalCell), flat(), SURVIVAL, new BlockPos(4, Y, 0)));
    }
}
