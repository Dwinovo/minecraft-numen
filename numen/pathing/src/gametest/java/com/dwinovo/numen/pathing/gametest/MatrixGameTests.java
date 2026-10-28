package com.dwinovo.numen.pathing.gametest;

import static com.dwinovo.numen.pathing.gametest.Trial.ARENA;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.gametest.MatrixCatalog.Variant;
import com.dwinovo.numen.pathing.gametest.MatrixScene.Base;
import com.dwinovo.numen.pathing.gametest.MatrixScene.Level;
import com.dwinovo.numen.pathing.gametest.MatrixScene.Where;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * 精确判过的步在执行里走得通的矩阵({@link Matrix}):每个家族的方块,摆在地面、挡在身体要过的空间里、当落脚点,对每种走法每个方向用精确的前提
 * 判成不成立,成立的让新身体只做这一步。精确判断成立而执行走不通的,每一处都是一条前提不够保守的缺陷。
 */
public class MatrixGameTests {

    private static final String BATCH = "pathing_matrix";
    /** 一条用例最多跑这么多刻(场景并行铺在 16 格上,按批铺满);最慢的 matrix_rotated 实测 7098 刻,留三倍。 */
    private static final int LIMIT = 21500;

    @BeforeBatch(batch = BATCH)
    public static void settle(ServerLevel level) {
        Worlds.settle(level);
    }

    /** 默认只走不改的规格,放开跑酷。 */
    private static RouteSpec walking() {
        return RouteSpec.defaults().edit().parkour(true).build();
    }

    private static final java.util.Set<String> HOLLOW = java.util.Set.of("cauldron", "hopper", "composter", "brewing_stand");
    private static final java.util.Set<String> FALLING = java.util.Set.of("sand", "anvil", "water_source");

    private static final List<Base> DRY = List.of(Base.FLAT, Base.DIAG, Base.UP, Base.DOWN, Base.DROP, Base.GAP, Base.GAP2);
    private static final List<Base> WET = List.of(Base.POOL, Base.SHORE, Base.SHORE_HIGH, Base.ENTER, Base.WADE);

    /** 这些方块样子 × 这些地形 × 摆在哪几列哪一层 × 转几圈,铺成场景。 */
    private static List<MatrixScene> scenes(List<Variant> variants, List<Base> bases, int... rots) {
        return scenes(variants, bases, List.of(Where.values()), rots);
    }

    private static List<MatrixScene> scenes(List<Variant> variants, List<Base> bases, List<Where> wheres, int... rots) {
        List<MatrixScene> out = new ArrayList<>();
        for (Variant variant : variants) {
            for (Base base : bases) {
                for (Where where : wheres) {
                    if (where == Where.SIDES && base != Base.DIAG) {
                        continue;
                    }
                    if (where == Where.ALL && HOLLOW.contains(variant.name())) {
                        // 镂空的方块一格紧挨一格铺满当地面(炼药锅、漏斗连成一片):没有人这么铺,不在矩阵里
                        continue;
                    }
                    for (Level level : Level.values()) {
                        if (level == Level.CEILING) {
                            continue;
                        }
                        if (level == Level.HEAD && FALLING.contains(variant.name())) {
                            // 悬在半空会掉下来的方块,摆在头那一层没有意义
                            continue;
                        }
                        for (int rot : rots) {
                            out.add(new MatrixScene(base, where, level, variant, rot));
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void run(GameTestHelper helper, List<MatrixScene> scenes) {
        new Matrix(helper, scenes, walking(), LIMIT, body -> Trial.NO_MATERIALS, body -> {});
    }

    private static List<Base> both() {
        List<Base> bases = new ArrayList<>(DRY);
        bases.addAll(WET);
        return bases;
    }

    /** 对照:各种地形本身,什么也不摆。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_terrain(GameTestHelper helper) {
        List<MatrixScene> out = new ArrayList<>();
        for (Base base : both()) {
            for (int rot = 0; rot < 4; rot++) {
                out.add(new MatrixScene(base, Where.START, Level.FEET, MatrixCatalog.NONE, rot));
            }
        }
        run(helper, out);
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_slabs(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.slabs(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_stairs(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.stairs(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_doors(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.doors(), DRY, 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_trapdoors(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.trapdoors(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_fences(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.fences(), DRY, 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_thin(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.thin(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_floors(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.floors(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_low(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.low(), both(), 0));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_hazards(GameTestHelper helper) {
        // 站在危险的方块上本来就疼,那不是这一步的事:危险方块只摆在目的地与斜走擦着的那几列
        run(helper, scenes(MatrixCatalog.hazards(), DRY, List.of(Where.DEST, Where.SIDES), 0));
    }

    /** 场景转三个四分之一圈(西、南、北):每个家族挑几种朝向敏感的方块,四个方向都走一遍。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_rotated(GameTestHelper helper) {
        List<Variant> picks = new ArrayList<>();
        pick(picks, MatrixCatalog.slabs(), "slab_bottom", "slab_top");
        pick(picks, MatrixCatalog.stairs(), "stair_bottom_e", "stair_top_s");
        pick(picks, MatrixCatalog.doors(), "oak_door_shut_e", "oak_door_open_s");
        pick(picks, MatrixCatalog.trapdoors(), "oak_trapdoor_bottom_shut", "oak_trapdoor_top_open_e");
        pick(picks, MatrixCatalog.fences(), "oak_fence", "fence_gate_shut_e");
        pick(picks, MatrixCatalog.thin(), "carpet", "snow_4");
        pick(picks, MatrixCatalog.floors(), "soul_sand");
        pick(picks, MatrixCatalog.low(), "bed", "chest");
        run(helper, scenes(picks, both(), 1, 2, 3));
    }

    private static void pick(List<Variant> into, List<Variant> from, String... names) {
        for (Variant variant : from) {
            if (List.of(names).contains(variant.name())) {
                into.add(variant);
            }
        }
    }

    /** 许挖许放、身上有镐有料:垫柱往上垫(头顶有东西压着、脚下是各种方块),向下挖穿各种方块。 */
    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_altering(GameTestHelper helper) {
        List<Variant> variants = new ArrayList<>();
        variants.addAll(MatrixCatalog.slabs());
        variants.addAll(MatrixCatalog.stairs());
        variants.addAll(MatrixCatalog.thin());
        variants.addAll(MatrixCatalog.floors());
        variants.addAll(MatrixCatalog.trapdoors());
        variants.add(MatrixCatalog.NONE);
        List<MatrixScene> out = new ArrayList<>();
        for (Variant variant : variants) {
            out.add(new MatrixScene(Base.RISE, Where.START, Level.CEILING, variant, 0));
            out.add(new MatrixScene(Base.RISE, Where.START, Level.FLOOR, variant, 0));
            out.add(new MatrixScene(Base.SINK, Where.START, Level.FLOOR, variant, 0));
        }
        new Matrix(helper, out, RouteSpec.defaults().edit().changes(true).build(), LIMIT,
                body -> Trial.carried(body, Blocks.COBBLESTONE),
                body -> Trial.give(body, new ItemStack(Items.IRON_PICKAXE), new ItemStack(Items.COBBLESTONE, 64)));
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_climbing(GameTestHelper helper) {
        List<MatrixScene> out = new ArrayList<>();
        for (Variant variant : MatrixCatalog.climbables()) {
            out.add(new MatrixScene(Base.COLUMN, Where.START, Level.FEET, variant, 0));
        }
        run(helper, out);
    }

    @GameTest(template = ARENA, batch = BATCH, timeoutTicks = LIMIT + 200)
    public static void matrix_fluids(GameTestHelper helper) {
        run(helper, scenes(MatrixCatalog.fluids(), both(), 0));
    }
}
