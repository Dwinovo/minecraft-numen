package com.dwinovo.numen.core.bench;

import com.dwinovo.numen.bench.Arena;
import com.dwinovo.numen.bench.Check;
import com.dwinovo.numen.bench.Scenario;
import com.dwinovo.numen.bench.Scene;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * 去远处:一百一十格见方的石头平地,她站在西头;正东约一百格外立着一根十格高的圆石柱,半路一条五格宽、三格深的河横贯
 * 整个场地(绕不过去,得游或者搭桥)。主人只说往东走到那根石柱跟前——不给坐标,场地原点每次都不一样。
 * 成功 = 她停在石柱四格以内(水平距离)。
 */
public final class FarPillar implements Scenario {

    private static final Arena ARENA = new Arena(110, 12);
    private static final int ROW = 55;
    private static final BlockPos PILLAR = new BlockPos(104, 1, ROW);
    private static final int PILLAR_HEIGHT = 10;
    /** 河占的 x(含两端)。 */
    private static final int RIVER_WEST = 50;
    private static final int RIVER_EAST = 54;
    private static final double NEAR = 4;

    @Override
    public String id() {
        return "walk_to_far_pillar";
    }

    @Override
    public Arena arena() {
        return ARENA;
    }

    @Override
    public BlockPos start() {
        return new BlockPos(5, 1, ROW);
    }

    @Override
    public BlockPos ownerAt() {
        return new BlockPos(4, 1, ROW - 2);
    }

    @Override
    public void setup(Scene scene) {
        for (int x = RIVER_WEST; x <= RIVER_EAST; x++) {
            for (int z = 0; z < ARENA.size(); z++) {
                for (int y = -2; y <= 0; y++) {
                    scene.set(x, y, z, Blocks.WATER.defaultBlockState());
                }
            }
        }
        for (int y = PILLAR.getY(); y < PILLAR.getY() + PILLAR_HEIGHT; y++) {
            scene.set(PILLAR.getX(), y, PILLAR.getZ(), Blocks.COBBLESTONE.defaultBlockState());
        }
    }

    @Override
    public String opening() {
        return "往东一直走,走到那根高高的石柱跟前去。";
    }

    @Override
    public List<Check> checks() {
        return List.of(
                Check.success("到了石柱跟前", s -> s.assertTrue(distance(s) <= NEAR,
                        "离石柱还有 " + Math.round(distance(s)) + " 格")),
                Check.subgoal("过了河", s -> s.assertTrue(s.her().getX() > s.pos(RIVER_EAST, 0, 0).getX() + 1,
                        "她还在河西边")));
    }

    /** 她离石柱的水平距离。 */
    private static double distance(Scene scene) {
        BlockPos p = scene.pos(PILLAR);
        double dx = scene.her().getX() - (p.getX() + 0.5);
        double dz = scene.her().getZ() - (p.getZ() + 0.5);
        return Math.sqrt(dx * dx + dz * dz);
    }

    @Override
    public String solution(Scene scene) {
        BlockPos p = scene.pos(PILLAR);
        return "move.goto_({x = " + (p.getX() - 2) + ", z = " + p.getZ() + "})";
    }
}
