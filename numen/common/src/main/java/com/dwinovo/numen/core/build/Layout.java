package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.task.build.BuildTaskRecord;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;

/**
 * 摆到世界里的一份施工图:每一格要成什么(世界坐标),外加只有图纸文件才带的几样——方块实体数据、摆设实体、逐格料单——
 * 以及<b>摆的时候就掉掉的格数</b>。设计、蓝图文件、当场执行的一个原语,摆下去都是它,交给同一个执行器。
 *
 * <p>掉格要有账:一张一千格的图纸掉了两百格,不记的话任务会报"八百格全部达标",缺的那五分之一无人知晓。
 *
 * @param size 占地尺寸(x × y × z)
 */
public record Layout(List<BuildTaskRecord.Target> targets, Vec3i size,
                     Map<Long, CompoundTag> blockEntityData,
                     List<BuildTaskRecord.EntitySpawn> entities,
                     Map<Long, List<BuildTaskRecord.CellNeed>> cellNeeds,
                     int dropped) {

    public Layout {
        targets = List.copyOf(targets);
        blockEntityData = Map.copyOf(blockEntityData);
        entities = List.copyOf(entities);
        cellNeeds = Map.copyOf(cellNeeds);
    }

    /** 只有方块的一份:原语画出来的格子。尺寸按格子的外接盒算。 */
    public static Layout of(List<BuildTaskRecord.Target> targets, int dropped) {
        return new Layout(targets, extent(targets), Map.of(), List.of(), Map.of(), dropped);
    }

    /** 这些格子的外接盒有多大;没有格子是 0×0×0。 */
    static Vec3i extent(List<BuildTaskRecord.Target> targets) {
        if (targets.isEmpty()) {
            return Vec3i.ZERO;
        }
        BlockPos first = targets.get(0).pos();
        int[] lo = {first.getX(), first.getY(), first.getZ()};
        int[] hi = lo.clone();
        for (BuildTaskRecord.Target t : targets) {
            int[] at = {t.pos().getX(), t.pos().getY(), t.pos().getZ()};
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(lo[i], at[i]);
                hi[i] = Math.max(hi[i], at[i]);
            }
        }
        return new Vec3i(hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1);
    }
}
