package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.Fixtures;
import com.dwinovo.numen.pathing.TestWorld;
import com.dwinovo.numen.pathing.Vanilla;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.pathing.plan.Steps.holds;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 憋气:原版的掉氧与回气(满氧气 300 刻、水下呼吸附魔、水下呼吸与潮涌能量效果、海龟壳、能在水下呼吸与无敌),眼睛换不换得了气,
 * 一步憋不憋着气、要几刻。
 */
class BreathTest {

    private static final int Y = 64;
    private static final BlockPos AT = new BlockPos(0, Y, 0);
    private static final Heading EAST = new Heading(1, 0, 0);

    @BeforeAll
    static void boot() {
        Vanilla.boot();
    }

    private static BlockState water() {
        return Blocks.WATER.defaultBlockState();
    }

    // ==================== 原版规则 ====================

    /** 原版满氧气 300 点,眼睛泡在水里每刻扣 1:憋 240 刻还留着 60 刻的余量,再多一刻就不算憋得住;规划说得出还能安全憋多久。 */
    @Test
    void aVanillaBodyHoldsThreeHundredTicksAndKeepsThreeSecondsSpare() {
        Breath breath = Breath.VANILLA;
        Breath.Air now = breath.now();
        assertEquals(300, now.left());
        assertEquals(240, breath.spare(now));
        Breath.Air held = breath.after(now, true, 240);
        assertEquals(60, held.lungs());
        assertEquals(240, held.held());
        assertTrue(breath.lasts(held));
        assertFalse(breath.lasts(breath.after(now, true, 241)));
    }

    /** 属性 oxygen_bonus(水下呼吸附魔每级 1)为 b 时平均每刻只扣 1/(b+1):水下呼吸 III 同样的氧气憋四倍久。 */
    @Test
    void respirationStretchesTheSameAirOverMoreTicks() {
        Breath respiration = new Breath(300, 300, 3, 0, false);
        assertEquals(1200, respiration.now().left());
        assertTrue(respiration.lasts(respiration.after(respiration.now(), true, 1100)));
        assertFalse(respiration.lasts(respiration.after(respiration.now(), true, 1150)));
    }

    /** 水下呼吸类效果先耗:效果还剩 600 刻时,憋 700 刻只从肺里扣 100。 */
    @Test
    void waterBreathingIsSpentBeforeTheLungs() {
        Breath potion = new Breath(300, 300, 0, 600, false);
        Breath.Air air = potion.after(potion.now(), true, 700);
        assertEquals(0, air.shield());
        assertEquals(200, air.lungs());
        assertEquals(900, potion.now().left());
    }

    /** 戴海龟壳:眼睛出水的每一刻把水下呼吸续到 200 刻,所以每次下水先有 10 秒不扣氧;下了水就不再续。 */
    @Test
    void aTurtleShellGivesTenSecondsEveryTimeTheHeadGoesUnder() {
        Breath shell = new Breath(300, 300, 0, 0, true);
        Breath.Air surfaced = shell.after(shell.now(), false, 1);
        assertEquals(Breath.TURTLE_SHELL_TICKS, surfaced.shield());
        Breath.Air under = shell.after(surfaced, true, 250);
        assertEquals(0, under.shield());
        assertEquals(250, under.lungs());
        Breath.Air again = shell.after(under, false, 5);
        assertEquals(Breath.TURTLE_SHELL_TICKS, again.shield(), "出水又续满");
        assertEquals(270, again.lungs(), "每刻回 4 点");
    }

    /** 出水每刻回 4 点、回满为止——半路露一下头换不满;换过气这一段就重新计。 */
    @Test
    void theHeadAboveWaterRefillsFourATickUpToFull() {
        Breath empty = new Breath(0, 300, 0, 0, false);
        Breath.Air low = empty.after(empty.now(), false, 10);
        assertEquals(40, low.lungs());
        assertEquals(0, low.held());
        assertEquals(300, empty.after(empty.now(), false, 100).lungs(), "回满为止");
    }

    /** 能在水下呼吸、无敌(创造模式)的身体原版不扣氧:憋多久都憋得住,也永远是气息平稳的那一档。 */
    @Test
    void aBodyThatNeverDrownsHoldsForever() {
        Breath never = new Breath(300, 300, 0, Double.POSITIVE_INFINITY, false);
        Breath.Air air = never.after(never.now(), true, 1_000_000);
        assertTrue(never.lasts(air));
        assertEquals(0, never.band(air));
        assertTrue(Breath.VANILLA.lasts(Breath.VANILLA.after(Breath.UNLIMITED, true, 1_000_000)));
    }

    /** 搜索分节点的档:肺里满、没在憋的是 0 档;憋着气的按还能撑多久分档,撑得久的与撑得短的不在一档。 */
    @Test
    void holdingBreathSplitsTheSearchIntoBands() {
        Breath breath = Breath.VANILLA;
        assertEquals(0, breath.band(breath.now()));
        assertTrue(breath.rested(breath.now()));
        Breath.Air shortly = breath.after(breath.now(), true, 20);
        Breath.Air long_ = breath.after(breath.now(), true, 200);
        assertFalse(breath.rested(shortly));
        assertNotEquals(0, breath.band(shortly));
        assertNotEquals(breath.band(shortly), breath.band(long_));
        assertFalse(new Breath(300, 300, 0, 100, false).rested(new Breath(300, 300, 0, 100, false).now()),
                "水下呼吸效果在流逝时要推算");
    }

    // ==================== 眼睛与一步 ====================

    /** 眼睛泡在水里换不了气;眼睛在气泡柱里原版不扣氧、照常回气;眼睛在空气里当然换得了。 */
    @Test
    void theEyeCannotBreatheInWaterButCanInABubbleColumn() {
        TestWorld world = new TestWorld().set(0, Y, 0, water()).set(1, Y, 0, Blocks.BUBBLE_COLUMN.defaultBlockState());
        assertTrue(Semantics.breathless(world, 0.5, Y + 0.5, 0.5));
        assertFalse(Semantics.breathless(world, 1.5, Y + 0.5, 0.5), "气泡柱");
        assertFalse(Semantics.breathless(world, 2.5, Y + 0.5, 0.5), "空气");
    }

    /** 两格深的池底平挪,眼睛在水里:这一步憋着气;浮在水面上平挪、一格深的浅水里蹚,眼睛在水面之上:不憋。 */
    @Test
    void aStepIsSubmergedWhenTheEyeIsUnderWater() {
        CostModel model = Fixtures.model(RouteSpec.defaults());
        TestWorld deep = new TestWorld().floor(-4, -4, 6, 4, Y - 1).fill(-2, Y, -2, 4, Y + 1, 2, water());
        Maneuver bottom = holds(MoveKind.WALK, model, deep, AT, EAST);
        assertTrue(bottom.submerged(), "池底:眼睛在水里");
        Maneuver surface = holds(MoveKind.SWIM, model, deep, AT.above(), EAST);
        assertFalse(surface.submerged(), "水面:眼睛在水面之上");
        TestWorld shallow = new TestWorld().floor(-4, -4, 6, 4, Y - 1).fill(-2, Y, -2, 4, Y, 2, water());
        assertFalse(holds(MoveKind.WALK, model, shallow, AT, EAST).submerged(), "一格深的浅水");
    }

    /**
     * 一步要几刻只算身体真花的工夫,罚分与加价不算:水里一格是水里的步速,价钱多出涉水罚分;挖一格的刻数是挖到碎连同缓手,
     * 价钱多出挖掘罚分。
     */
    @Test
    void ticksLeaveOutThePenaltiesThatThePriceAdds() {
        CostModel model = Fixtures.model(RouteSpec.defaults());
        TestWorld deep = new TestWorld().floor(-4, -4, 6, 4, Y - 1).fill(-2, Y, -2, 4, Y + 1, 2, water());
        Maneuver swim = holds(MoveKind.WALK, model, deep, AT, EAST);
        Move walk = Moves.of(MoveKind.WALK);
        assertEquals(model.waterStep(), walk.ticks(model, swim), 1e-9);
        assertEquals(model.spec().wadePenalty(), walk.cost(model, swim) - walk.ticks(model, swim), 1e-9);

        CostModel digging = Fixtures.model(Fixtures.natural());
        TestWorld wall = new TestWorld().floor(-4, -4, 6, 4, Y - 1).fill(1, Y, -4, 1, Y + 1, 4,
                Blocks.DIRT.defaultBlockState());
        Maneuver dig = holds(MoveKind.WALK, digging, wall, AT, EAST);
        assertEquals(2, dig.alterations());
        double work = digging.workTicks(dig);
        assertTrue(work > 0);
        assertEquals(Strides.pace(digging, dig) + work, walk.ticks(digging, dig), 1e-9);
        assertEquals(2 * digging.spec().breakPenalty(), walk.cost(digging, dig) - walk.ticks(digging, dig), 1e-9);
    }
}
