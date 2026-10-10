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
    private static final int TIMEOUT = 6000;

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

    @GameTest(template = TEMPLATE, batch = "terrain_ravine_floor", timeoutTicks = TIMEOUT)
    public static void ravine_floor(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.RAVINE_FLOOR);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_ravine_cross", timeoutTicks = TIMEOUT)
    public static void ravine_cross(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.RAVINE_CROSS);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_ridge_detour", timeoutTicks = TIMEOUT)
    public static void ridge_detour(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.RIDGE_DETOUR);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_hill_detour", timeoutTicks = TIMEOUT)
    public static void hill_detour(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.HILL_DETOUR);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_basin_descent", timeoutTicks = TIMEOUT)
    public static void basin_descent(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.BASIN_DESCENT);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_long_300", timeoutTicks = TIMEOUT)
    public static void long_300(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.LONG_300);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_broken", timeoutTicks = TIMEOUT)
    public static void broken(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.BROKEN);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_dense_forest", timeoutTicks = TIMEOUT)
    public static void dense_forest(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.DENSE_FOREST);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_lake_cross", timeoutTicks = TIMEOUT)
    public static void lake_cross(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.LAKE_CROSS);
    }

    @GameTest(template = TEMPLATE, batch = "terrain_lake_shallows", timeoutTicks = TIMEOUT)
    public static void lake_shallows(GameTestHelper helper) {
        TerrainRun.start(helper, TerrainRoutes.LAKE_SHALLOWS);
    }
}
