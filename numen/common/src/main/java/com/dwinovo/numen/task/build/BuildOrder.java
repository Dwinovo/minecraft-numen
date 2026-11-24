package com.dwinovo.numen.task.build;

import net.minecraft.world.phys.Vec3;

import java.util.Comparator;

/**
 * 施工顺序与节奏的<b>唯一定义</b>:先后(比较器)与快慢(每刻几格、时长估计与时限)。全是无状态纯函数——派发方
 * ({@link #deadlineTicks})与施工任务共用同一份,不许各拍各的。
 */
public final class BuildOrder {

    private BuildOrder() {}

    /** 每刻最多落几格:生存与创造同一个速度。 */
    public static final int CELLS_PER_TICK = 30;

    /**
     * 施工顺序:低层先(下面盖好了上面才有依托),同一层里离 {@code from}(她站的地方)近的先。末两项只为顺序完全确定。
     */
    public static Comparator<BuildTaskRecord.Target> from(Vec3 from) {
        return Comparator
                .comparingInt((BuildTaskRecord.Target t) -> t.pos().getY())
                .thenComparingDouble(t -> {
                    double dx = t.pos().getX() + 0.5 - from.x;
                    double dz = t.pos().getZ() + 0.5 - from.z;
                    return dx * dx + dz * dz;
                })
                .thenComparingInt(t -> t.pos().getZ())
                .thenComparingInt(t -> t.pos().getX());
    }

    /** 施工预计要多少刻——派发方据此定时限。 */
    public static long estimatedTicks(int cellCount) {
        return (long) Math.ceil(Math.max(1, cellCount) / (double) CELLS_PER_TICK);
    }

    private static final long MIN_DEADLINE_TICKS = 60 * 20;
    /** 施工预计时长之上再留的余量。 */
    private static final double DEADLINE_SLACK = 1.6;

    /** 施工时限:施工预计时长再留一截余量。预计时长就是 {@link #estimatedTicks}。 */
    public static long deadlineTicks(int cellCount) {
        return Math.max(MIN_DEADLINE_TICKS, (long) (estimatedTicks(cellCount) * DEADLINE_SLACK));
    }
}
