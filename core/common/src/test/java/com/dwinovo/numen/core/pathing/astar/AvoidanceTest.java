package com.dwinovo.numen.core.pathing.astar;

import java.util.List;

import com.dwinovo.numen.core.pathing.goals.GoalApproachAvoiding;
import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;
import com.dwinovo.numen.core.pathing.goals.GoalBlock;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Avoidance 球形惩罚的纯逻辑钉桩:系数只在球内生效、边界点算入、
 * applySpherical 叠乘已有值、create 在 avoidance 关闭时返回空表;
 * 以及惩罚球从<b>目标自带的威胁表</b>来,每只按自己的危险半径。
 */
class AvoidanceTest {

    @Test
    void coefficientInsideSphereOnly() {
        Avoidance a = new Avoidance(0, 64, 0, 1.5, 8);
        // 中心
        assertEquals(1.5, a.coefficient(0, 64, 0), 1e-9);
        // 边界点(距离平方恰为 64)
        assertEquals(1.5, a.coefficient(8, 64, 0), 1e-9);
        // 球外
        assertEquals(1.0, a.coefficient(9, 64, 0), 1e-9);
        // y 方向也生效
        assertEquals(1.5, a.coefficient(0, 72, 0), 1e-9);
        assertEquals(1.0, a.coefficient(0, 73, 0), 1e-9);
    }

    @Test
    void applySphericalMultipliesExistingValue() {
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap map =
                new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        map.defaultReturnValue(1.0D);
        // 预置一个 backtrack 折扣 0.5 在 (0,64,0)
        map.put(PathNode.longHash(0, 64, 0), 0.5);
        Avoidance mob = new Avoidance(0, 64, 0, 1.5, 4);
        mob.applySpherical(map);
        // 球内 (0,64,0):0.5 * 1.5 = 0.75
        assertEquals(0.75, map.get(PathNode.longHash(0, 64, 0)), 1e-9);
        // 球外默认 1.0(未写入)
        assertEquals(1.0, map.get(PathNode.longHash(10, 64, 0)), 1e-9);
    }

    @Test
    void multipleSpheresOverlapMultiply() {
        it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap map =
                new it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap();
        map.defaultReturnValue(1.0D);
        Avoidance mob = new Avoidance(0, 64, 0, 1.5, 8);
        Avoidance spawner = new Avoidance(0, 64, 0, 2.0, 16);
        mob.applySpherical(map);
        spawner.applySpherical(map);
        // 同心点:1.0 * 1.5 * 2.0 = 3.0
        assertEquals(3.0, map.get(PathNode.longHash(0, 64, 0)), 1e-9);
        // 仅在刷怪笼球内(距 12,超出 mob 半径 8):1.0 * 2.0 = 2.0
        assertEquals(2.0, map.get(PathNode.longHash(12, 64, 0)), 1e-9);
        // 两球外:1.0
        assertEquals(1.0, map.get(PathNode.longHash(20, 64, 0)), 1e-9);
    }

    @Test
    void createReturnsEmptyWhenAvoidanceDisabled() {
        com.dwinovo.numen.core.pathing.settings.NavSettings s =
                com.dwinovo.numen.core.pathing.settings.NavSettings.get();
        boolean saved = s.avoidance;
        s.avoidance = false;
        try {
            List<Avoidance> res = Avoidance.create(null);
            assertTrue(res.isEmpty(), "avoidance 关闭时应返回空表");
        } finally {
            s.avoidance = saved;
        }
    }

    // ==================== 惩罚球从目标自带的威胁表来 ====================

    private static GoalAvoidEntities.Threat threat(double x, double z, double radius) {
        return new GoalAvoidEntities.Threat(x, 64.0, z, radius);
    }

    /** 威胁表在 GoalAvoidEntities 里,直接认得出来。 */
    @Test
    void aBareFieldIsRecognised() {
        var field = new GoalAvoidEntities(40.0, threat(0, 0, 3.0), threat(9, 0, 6.0));
        assertEquals(2, Avoidance.forGoal(field, null).size());
    }

    /** 包在站位目标里也认得出来 —— 站位的估价是纯距离,躲避全靠这一层边成本。 */
    @Test
    void theFieldInsideAStandoffGoalIsRecognised() {
        var wrapped = new GoalApproachAvoiding(new GoalBlock(10, 64, 0),
                new GoalAvoidEntities(40.0, threat(0, 0, 3.0)));
        assertEquals(1, Avoidance.forGoal(wrapped, null).size());
    }

    /**
     * 每只按<b>自己的</b>危险半径。用一个统一的数只能取最大值,于是她躲僵尸也按点着的
     * 苦力怕那个距离躲;取最小值又拦不住会炸的。
     */
    @Test
    void eachThreatKeepsItsOwnRadius() {
        var field = new GoalAvoidEntities(40.0, threat(0, 0, 2.73), threat(20, 0, 6.71));
        var spheres = Avoidance.forGoal(field, null);
        assertTrue(spheres.get(0).coefficient(2, 64, 0) > 1.0);    // 僵尸:三格内贵
        assertEquals(1.0, spheres.get(0).coefficient(4, 64, 0));   // 三格外不贵
        assertTrue(spheres.get(1).coefficient(26, 64, 0) > 1.0);   // 苦力怕:六格还贵
    }
}
