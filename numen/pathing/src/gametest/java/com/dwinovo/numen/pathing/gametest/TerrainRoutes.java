package com.dwinovo.numen.pathing.gametest;

import java.util.List;
import net.minecraft.core.BlockPos;

/**
 * 固定存档 {@code hills}(种子 0,区域 r.0.0,见 terrain/README.md)上的固定路线。起终点是站立的那一格(地面之上一格),
 * 常量是从制作存档时的勘测表上挑的,重做存档要重新挑。路线全程避开水(两侧各两格内都没有)。
 *
 * <p>类型:平地直行、上坡、下坡、陡坎(中途有一级 5 格以上的落差)、穿树林、树林里上坡、绕障碍(直线中间隔着 30 格以上的断崖)、
 * 起伏、长距离(160 格)。时限是够从容走完的刻数,超过就算没走到。
 *
 * <p>后一批是难路线,全是地形里天然存在的:峡谷(30 格深的裂缝)底、跨峡谷、被高地或山脊挡住要绕远、掉进盆地、300 格以上的长路、
 * 破碎地形、密林。起终点按勘测表挑,没有照寻路能不能走通来挑;走不通的原样记着,是后面分析的素材。
 */
final class TerrainRoutes {

    private TerrainRoutes() {}

    /** 一条固定路线。 */
    record Route(String name, String kind, BlockPos from, BlockPos to, int limit) {

        /** 起终点的水平直线距离。 */
        double distance() {
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }

        /** 终点比起点高几格。 */
        int rise() {
            return to.getY() - from.getY();
        }
    }

    static final Route FLAT = new Route("flat", "平地直行", new BlockPos(343, 67, 212), new BlockPos(252, 63, 133), 1500);
    static final Route UPHILL = new Route("uphill", "上坡", new BlockPos(340, 68, 257), new BlockPos(226, 106, 294), 2000);
    static final Route DOWNHILL = new Route("downhill", "下坡", new BlockPos(238, 105, 298), new BlockPos(344, 67, 241), 2000);
    static final Route STEP = new Route("step", "陡坎", new BlockPos(290, 64, 165), new BlockPos(254, 66, 213), 1200);
    static final Route FOREST = new Route("forest", "穿树林", new BlockPos(128, 71, 343), new BlockPos(155, 68, 268), 1500);
    static final Route FOREST_UPHILL = new Route("forest_uphill", "树林上坡", new BlockPos(231, 85, 355), new BlockPos(213, 106, 298), 1500);
    static final Route WALL = new Route("wall", "绕障碍", new BlockPos(251, 70, 238), new BlockPos(311, 68, 238), 1500);
    static final Route LONG_A = new Route("long_a", "长距离", new BlockPos(311, 65, 174), new BlockPos(348, 72, 330), 3000);
    static final Route LONG_B = new Route("long_b", "长距离", new BlockPos(132, 69, 359), new BlockPos(193, 69, 211), 3000);
    static final Route ROLLING = new Route("rolling", "起伏", new BlockPos(306, 78, 273), new BlockPos(213, 69, 237), 2000);

    static final Route RAVINE_FLOOR = new Route("ravine_floor", "下到峡谷底", new BlockPos(300, 65, 228), new BlockPos(278, 32, 242), 1200);
    static final Route RAVINE_CROSS = new Route("ravine_cross", "跨峡谷", new BlockPos(300, 66, 215), new BlockPos(262, 87, 262), 1500);
    static final Route RIDGE_DETOUR = new Route("ridge_detour", "山脊挡路绕远", new BlockPos(123, 69, 164), new BlockPos(83, 95, 203), 2000);
    static final Route HILL_DETOUR = new Route("hill_detour", "高地挡路绕远", new BlockPos(340, 64, 411), new BlockPos(300, 86, 371), 2000);
    static final Route BASIN_DESCENT = new Route("basin_descent", "下到盆地", new BlockPos(168, 72, 359), new BlockPos(245, 44, 425), 2500);
    static final Route LONG_300 = new Route("long_300", "300格长路", new BlockPos(70, 70, 130), new BlockPos(440, 86, 100), 4000);
    static final Route BROKEN = new Route("broken", "破碎地形", new BlockPos(205, 88, 398), new BlockPos(300, 87, 440), 2500);
    static final Route DENSE_FOREST = new Route("dense_forest", "密林", new BlockPos(403, 70, 64), new BlockPos(444, 86, 104), 2000);

    static final List<Route> ALL = List.of(FLAT, UPHILL, DOWNHILL, STEP, FOREST, FOREST_UPHILL, WALL, LONG_A, LONG_B, ROLLING,
            RAVINE_FLOOR, RAVINE_CROSS, RIDGE_DETOUR, HILL_DETOUR, BASIN_DESCENT, LONG_300, BROKEN, DENSE_FOREST);
}
