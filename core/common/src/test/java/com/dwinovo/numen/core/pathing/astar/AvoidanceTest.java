package com.dwinovo.numen.core.pathing.astar;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Avoidance 球形惩罚的纯逻辑钉桩:系数只在球内生效、边界点算入、
 * applySpherical 叠乘已有值、create 在 avoidance 关闭时返回空表。
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
}