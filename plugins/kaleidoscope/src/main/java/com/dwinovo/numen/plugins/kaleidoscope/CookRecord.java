package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** {@code kaleidoscope cook} 派下来的一件活:在哪一格、做哪道菜。 */
public final class CookRecord extends TaskRecord {

    /**
     * 备料那一段的宽限:倒油、下料、盖盖、装盘这些一下一下的动作。
     *
     * <p>真正的炖煮时间在任务开跑、认出是哪道菜之后才按配方加上去——派活那一刻只收下配方 id,
     * 还不知道点的是炒十秒的蛋还是炖三分钟的汤。
     */
    static final int PREP_BUDGET_TICKS = 60 * 20;

    final BlockPos pos;
    final ResourceLocation recipe;

    CookRecord(ServerSource source, BlockPos pos, ResourceLocation recipe) {
        super(source, source.companion().level().getGameTime() + PREP_BUDGET_TICKS);
        this.pos = pos.immutable();
        this.recipe = recipe;
    }

    @Override
    public String describe() {
        return getToolName() + " " + recipe + " @ " + Cooker.where(pos);
    }
}
