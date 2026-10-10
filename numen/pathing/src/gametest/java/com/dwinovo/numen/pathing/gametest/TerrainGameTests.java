package com.dwinovo.numen.pathing.gametest;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 固定存档上的固定路线(套件 {@code numen_pathing_terrain},只用 {@code -Pgametest=numen_pathing_terrain} 手动选跑,不在日常批里)。
 * GameTest 场地是主世界里一格空结构,只负责计时与汇报;路线在真实地形维度里按绝对坐标跑。每条路线自成一批,逐条跑,
 * 互不抢刻。记录与判定见 {@link TerrainRun}。
 */
public class TerrainGameTests {

    private static final String TEMPLATE = "numen_pathing:pathing_empty";
    /** GameTest 自己的时限:比最长的路线时限(见 TerrainRoutes)再多留加载与出生无敌的刻数;各路线的时限由 TerrainRun 自己判。 */
    private static final int TIMEOUT = 5000;

    @GameTest(template = TEMPLATE, batch = "terrain_flat", timeoutTicks = TIMEOUT)
    public static void flat(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.FLAT);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_uphill", timeoutTicks = TIMEOUT)
    public static void uphill(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.UPHILL);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_downhill", timeoutTicks = TIMEOUT)
    public static void downhill(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.DOWNHILL);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_step", timeoutTicks = TIMEOUT)
    public static void step(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.STEP);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_forest", timeoutTicks = TIMEOUT)
    public static void forest(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.FOREST);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_forest_uphill", timeoutTicks = TIMEOUT)
    public static void forest_uphill(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.FOREST_UPHILL);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_wall", timeoutTicks = TIMEOUT)
    public static void wall(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.WALL);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_long_a", timeoutTicks = TIMEOUT)
    public static void long_a(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.LONG_A);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_long_b", timeoutTicks = TIMEOUT)
    public static void long_b(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.LONG_B);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_rolling", timeoutTicks = TIMEOUT)
    public static void rolling(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.ROLLING);
    }
}
