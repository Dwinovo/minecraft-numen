package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import com.dwinovo.numen.sdk.Positions;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 在两根柱子之间砌墙:两根三格高的栅栏柱子相距 5 格,之间 4 格宽的空位;她包里正好够用的圆石(4×3 = 12 块)。主人说在
 * 两根柱子之间砌一堵三格高的圆石墙。要成事得看清柱子在哪、空位多宽、墙该占哪些格,再一格不多一格不少地砌。
 * 成功 = 目标墙格的 F1 不低于 {@value #PASS}(目标格与实际圆石格的交集,精确率 = 交集 ÷ 实际圆石格,召回率 = 交集 ÷ 目标格,
 * 照 IGLU 的做法);负面 = 两根柱子还在。
 */
public final class BuildWall implements Scenario {

    private static final double PASS = 0.9;
    private static final int Z = 8;
    private static final int LEFT = 6;
    private static final int RIGHT = 11;
    private static final int HEIGHT = 3;

    @Override
    public String id() {
        return "build_wall_between_posts";
    }

    @Override
    public void setup(Scene scene) {
        for (int y = 1; y <= HEIGHT; y++) {
            scene.set(LEFT, y, Z, Blocks.OAK_FENCE.defaultBlockState());
            scene.set(RIGHT, y, Z, Blocks.OAK_FENCE.defaultBlockState());
        }
        scene.give(new ItemStack(Items.COBBLESTONE, target().size()));
    }

    @Override
    public String opening() {
        return "在那两根柱子之间砌一堵三格高的圆石墙。";
    }

    /** 目标墙格:两根柱子之间的空位,三层。 */
    private static Set<BlockPos> target() {
        Set<BlockPos> cells = new HashSet<>();
        for (int x = LEFT + 1; x < RIGHT; x++) {
            for (int y = 1; y <= HEIGHT; y++) {
                cells.add(new BlockPos(x, y, Z));
            }
        }
        return cells;
    }

    /** 场地里实际的圆石格(场地起初一块圆石都没有,都是她放的)。 */
    private List<BlockPos> cobble(Scene scene) {
        List<BlockPos> cells = new ArrayList<>();
        for (int x = 0; x < arena().size(); x++) {
            for (int z = 0; z < arena().size(); z++) {
                for (int y = 1; y <= arena().height(); y++) {
                    if (scene.level().getBlockState(scene.pos(x, y, z)).is(Blocks.COBBLESTONE)) {
                        cells.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return cells;
    }

    private double f1(Scene scene) {
        Set<BlockPos> target = target();
        List<BlockPos> actual = cobble(scene);
        long hit = actual.stream().filter(target::contains).count();
        if (hit == 0) {
            return 0;
        }
        double precision = hit / (double) actual.size();
        double recall = hit / (double) target.size();
        return 2 * precision * recall / (precision + recall);
    }

    private boolean postsStand(Scene scene) {
        for (int y = 1; y <= HEIGHT; y++) {
            if (!scene.level().getBlockState(scene.pos(LEFT, y, Z)).is(Blocks.OAK_FENCE)
                    || !scene.level().getBlockState(scene.pos(RIGHT, y, Z)).is(Blocks.OAK_FENCE)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("墙格 F1 不低于 " + PASS, s -> s.assertTrue(f1(s) >= PASS,
                        "F1 " + String.format("%.2f", f1(s)) + ",场地里圆石 " + cobble(s).size() + " 块,其中目标格 "
                                + cobble(s).stream().filter(target()::contains).count() + "/" + target().size())),
                Check.guard("两根柱子还在", s -> s.assertTrue(postsStand(s), "柱子少了一格")),
                Check.subgoal("放了圆石", s -> s.assertTrue(!cobble(s).isEmpty(), "一块圆石都没放")),
                Check.subgoal("墙格填满", s -> s.assertTrue(
                        cobble(s).stream().filter(target()::contains).count() == target().size(), "墙格没填满")));
    }

    @Override
    public String solution(Scene scene) {
        // numen.build.place 只放手够得着的格:站在墙南面正中,十二格都在手边;numen.build.raise 缺什么补什么
        BlockPos stand = scene.pos((LEFT + RIGHT) / 2, 1, Z - 2);
        BlockPos from = scene.pos(LEFT + 1, 1, Z);
        BlockPos to = scene.pos(RIGHT - 1, HEIGHT, Z);
        return """
                numen.move.to(%s)
                numen.build.raise(numen.shape.box(%s, %s, "cobblestone"))""".formatted(
                Positions.literal(stand), Positions.literal(from), Positions.literal(to));
    }
}
