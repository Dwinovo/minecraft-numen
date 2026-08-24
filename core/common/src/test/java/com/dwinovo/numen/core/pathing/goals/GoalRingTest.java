package com.dwinovo.numen.core.pathing.goals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 环形站位带。<b>钉的是估价,不是到达判定</b> —— 后者两个球一减就有,前者才是走位真正缺的。
 */
class GoalRingTest {

    private static final BlockPos CENTRE = new BlockPos(0, 64, 0);

    @Test
    void onlyTheBandCounts() {
        var ring = new GoalRing(CENTRE, 8.0, 12.0);
        assertTrue(ring.isInGoal(10, 64, 0));
        assertFalse(ring.isInGoal(3, 64, 0), "比内沿近不算到位");
        assertFalse(ring.isInGoal(20, 64, 0), "比外沿远不算到位");
    }

    /**
     * <b>太近时,往外走估价要变小。</b>
     *
     * <p>用球形邻域(估价 = 到中心的距离)时正好相反:弓的合格落点在八格开外,而她被怪贴到
     * 零点七格 —— 往外走每一步估价都变大,搜索于是往怪身上挖,{@code bestSoFar} 挑出来的
     * 是估价最小的那个节点,也就是贴脸那一格。实测她"寻路去贴着爬行者"。
     */
    @Test
    void tooCloseMeansOutwardIsCheaper() {
        var ring = new GoalRing(CENTRE, 8.0, 12.0);
        double atOne = ring.heuristic(1, 64, 0);
        double atFour = ring.heuristic(4, 64, 0);
        double atNine = ring.heuristic(9, 64, 0);
        assertTrue(atFour < atOne, "往外走了估价却更贵");
        assertTrue(atNine < atFour, "走到带边上估价该继续降");
        assertEquals(0.0, atNine, 1e-9, "带内该是零");
    }

    /** 太远时,往里走估价要变小 —— 两个方向都得有梯度。 */
    @Test
    void tooFarMeansInwardIsCheaper() {
        var ring = new GoalRing(CENTRE, 8.0, 12.0);
        assertTrue(ring.heuristic(14, 64, 0) < ring.heuristic(20, 64, 0));
        assertEquals(0.0, ring.heuristic(12, 64, 0), 1e-9);
    }

    /** 内沿比外沿还远(大史莱姆够得比玩家远):退化成实心球,无伤那条带本来就不存在。 */
    @Test
    void animpossibleBandFallsBackToASphere() {
        var ring = new GoalRing(CENTRE, 12.0, 8.0);
        assertTrue(ring.isInGoal(0, 64, 0));
        assertTrue(ring.isInGoal(7, 64, 0));
        assertFalse(ring.isInGoal(20, 64, 0));
    }

    /** 竖直不计:头顶三格不算退开了,脚下三格也不算贴上了。 */
    @Test
    void heightDoesNotCount() {
        var ring = new GoalRing(CENTRE, 8.0, 12.0);
        assertTrue(ring.isInGoal(10, 90, 0));
        assertFalse(ring.isInGoal(0, 90, 0));
    }
}
