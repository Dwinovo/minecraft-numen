package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一片区域抄一份画到别处——原版 {@code /clone} 的那条原语,外加旋转与镜像。抄的是画布上"前面几步画完之后"的样子
 * ({@link Canvas}):画过的格抄画上去的,没画过的抄底子——当场执行时底子是世界,设计里底子是空的。
 *
 * <p>对称的房子、重复的窗与柱,建一次再抄过去,比把每一格都写一遍便宜得多;而"抄过去
 * 再镜像"正是真实建造里最常用的一步。
 *
 * <p>朝向由原版算:方块状态走 {@link BlockState#mirror}/{@link BlockState#rotate},
 * 位置走 {@link StructureTemplate#transform}。楼梯的内外转角、门的合页、床的朝向,
 * 镜像时该怎么变是原版自己的规则,我们不另写一套(别处抄错这一步的项目,镜像出来的
 * 楼梯转角全是反的)。
 *
 * <p>源格里的空气默认<b>不抄</b>(原版 clone 的 masked 档):抄一片空气过去等于在目的地
 * 挖洞,而那通常不是"复制这栋楼"的意思。要连空也抄就把 {@code includeAir} 打开,
 * 让不让挖仍由这一格的让路档位说了算。
 */
public final class BuildCopy {

    private BuildCopy() {}

    /**
     * @param targets 抄出来的目标格
     * @param dropped 抄不了的格数(液体、基岩这类建不出来的东西)——如实报给她,不闷掉
     */
    public record Result(List<BuildTaskRecord.Target> targets, int dropped) {}

    /**
     * 抄 {@code from}..{@code to} 这一片,结果的最小角落在 {@code dest}(旋转之后源区域的哪个角落到那里,不必自己算)。
     * 只读画布,不往上画:抄出来的格由调用方画上去。
     */
    public static Result copy(Canvas canvas, BlockPos from, BlockPos to, BlockPos dest,
                            Rotation rotation, Mirror mirror, boolean includeAir) {
        BlockPos min = new BlockPos(Math.min(from.getX(), to.getX()),
                Math.min(from.getY(), to.getY()), Math.min(from.getZ(), to.getZ()));
        BlockPos max = new BlockPos(Math.max(from.getX(), to.getX()),
                Math.max(from.getY(), to.getY()), Math.max(from.getZ(), to.getZ()));
        long volume = (long) (max.getX() - min.getX() + 1)
                * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1);
        if (volume > BuildShapes.MAX_TOTAL_CELLS) {
            throw new IllegalArgumentException("copy covers " + volume + " cells, exceeding "
                    + BuildShapes.MAX_TOTAL_CELLS + "; copy a smaller region");
        }
        Rotation spin = rotation == null ? Rotation.NONE : rotation;
        Mirror flip = mirror == null ? Mirror.NONE : mirror;

        // 先把每一格变换到"相对落点"的坐标系里,记下最小角;抄过去的格按原来那一格的样子带着记账物品、车道与档位
        Map<BlockPos, Source> moved = new LinkedHashMap<>();
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int dropped = 0;
        for (BlockPos src : BlockPos.betweenClosed(min, max)) {
            BuildTaskRecord.Target drawn = canvas.drawn(src);
            BlockState state = drawn != null ? drawn.desiredState() : canvas.ground().state(src);
            if (state.isAir() && !includeAir) {
                continue;
            }
            if (!state.isAir() && BuildStates.unbuildableReason(state) != null) {
                dropped++;
                continue;
            }
            // 双格方块的次半不进目标集:主半落位时原版的回调自己会造它(与图纸入口同一条纪律)
            if (BuildStates.isSecondaryHalf(state)) {
                continue;
            }
            BlockPos rel = src.subtract(min);
            BlockPos at = StructureTemplate.transform(rel, flip, spin, BlockPos.ZERO);
            minX = Math.min(minX, at.getX());
            minZ = Math.min(minZ, at.getZ());
            moved.put(at, new Source(drawn, BuildStates.normalize(state).mirror(flip).rotate(spin)));
        }
        if (moved.isEmpty()) {
            throw new IllegalArgumentException("copy found nothing to copy in that region");
        }

        List<BuildTaskRecord.Target> out = new ArrayList<>(moved.size());
        for (Map.Entry<BlockPos, Source> e : moved.entrySet()) {
            BlockPos world = dest.offset(e.getKey().getX() - minX, e.getKey().getY(),
                    e.getKey().getZ() - minZ);
            BlockState placed = e.getValue().state();
            BuildTaskRecord.Target drawn = e.getValue().drawn();
            if (drawn != null) {
                out.add(drawn.placed(world, placed));
                continue;
            }
            if (placed.isAir()) {
                out.add(new BuildTaskRecord.Target(placed, Items.AIR, world, "air"));
                continue;
            }
            // 记账物品按这一格自己的坐标问方块(与图纸入口共用同一张判据)
            Item pay = canvas.ground().item(placed, world);
            if (pay == Items.AIR) {
                dropped++;
                continue;
            }
            out.add(new BuildTaskRecord.Target(placed, pay, world,
                    placed.getBlock().builtInRegistryHolder().key().location().getPath()));
        }
        return new Result(out, dropped);
    }

    /** 源区域里的一格:画布上画过的那一格(没画过是 null)与它变换之后的方块。 */
    private record Source(BuildTaskRecord.Target drawn, BlockState state) {}
}
