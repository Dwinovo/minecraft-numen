package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** {@code kaleidoscope.pot} 的一步派下来的那件活:在哪一格、哪一步、哪道菜(这一步用不着菜时为 null)。 */
public final class PotActRecord extends TaskRecord {

    /** 一下一下的那几步(倒油、下料、盖盖、装盘)的期限:够下满九格料还有余。 */
    private static final int STEP_TICKS = 30 * 20;

    /** 翻炒那一步跟着锅炒到好:炒得最久的菜也在这之内。 */
    private static final int STIR_TICKS = 5 * 60 * 20;

    final BlockPos pos;
    final PotAct act;
    final ResourceLocation recipe;

    PotActRecord(ServerCall source, BlockPos pos, PotAct act, ResourceLocation recipe) {
        super(source, source.her().level().getGameTime() + (act == PotAct.STIR ? STIR_TICKS : STEP_TICKS));
        this.pos = pos.immutable();
        this.act = act;
        this.recipe = recipe;
    }

    @Override
    public String describe() {
        return getToolName() + (recipe == null ? "" : " " + recipe) + " @ " + Cooker.where(pos);
    }
}
