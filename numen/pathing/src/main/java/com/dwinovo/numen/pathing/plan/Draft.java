package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.pathing.world.Faces;
import com.dwinovo.numen.pathing.world.Reach;
import com.dwinovo.numen.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一步的草稿:把这一步设想中的改动(挖掉、放下、开关门)叠在只读视图上({@link EditedView}),自己也是一个视图——几何
 * 照旧问第 0 层,问的是改完之后的世界。每一件改动在记下之前都过一遍成本模型的准入;第一件过不去的连同原因记为这一步的
 * 失败。
 *
 * <p>一次前提判断一份,用完即弃,不跨线程。
 */
final class Draft extends EditedView {

    private final CostModel model;
    private final BodyStats body;
    private final List<Edit> edits = new ArrayList<>(2);
    private Premise.Fails failure;

    Draft(CostModel model, WorldView base) {
        super(base);
        this.model = model;
        this.body = model.body().stats();
    }

    // ==================== 结果 ====================

    List<Edit> edits() {
        return edits;
    }

    /** 这一步的失败;还没失败为 null。 */
    Premise.Fails failure() {
        return failure;
    }

    /** 记下失败(只记第一件),返回 false 方便调用处直接 return。 */
    boolean fail(BlockPos cell, Reason reason) {
        return fail(cell, reason, null);
    }

    private boolean fail(BlockPos cell, Reason reason, Object detail) {
        if (failure == null) {
            failure = new Premise.Fails(cell.immutable(), reason, detail);
        }
        return false;
    }

    // ==================== 改动 ====================

    /**
     * 挖掉 {@code pos}:身体此刻脚在 {@code (bx, feetY, bz)} 站着挖。
     *
     * @param grounded 挖的时候脚踏实地
     */
    boolean dig(BlockPos pos, int bx, double feetY, int bz, boolean grounded) {
        BlockState state = getBlockState(pos);
        CostModel.Admission admission = model.admitDig(this, pos, state);
        if (!admission.ok()) {
            return fail(pos, admission.refused(), admission.detail());
        }
        if (!Reach.reaches(body, Pose.STANDING, bx, feetY, bz, pos)) {
            return fail(pos, Reason.OUT_OF_REACH);
        }
        boolean eyeInWater = Semantics.eyeInWater(this, bx + 0.5, feetY + body.eyeHeight(Pose.STANDING), bz + 0.5);
        edits.add(new Edit.Dig(pos.immutable(), state, admission.permit(), eyeInWater, grounded));
        dig(pos);
        return true;
    }

    /**
     * 同 {@link #place},另要站在 {@code (bx, feetY, bz)} 那一列中心的眼睛点得中一个面({@link Faces#inSight}):先站定再放的走法
     * (上一级垫一块台阶)执行时就是站在那儿瞄这个面,点不中就放不下。
     */
    boolean placeInSight(BlockPos pos, int bx, double feetY, int bz) {
        Block block = model.placing().orElse(null);
        if (block != null && Faces.inSight(this, Reach.eye(body, Pose.STANDING, bx, feetY, bz), body.blockReach(), pos,
                block) == null) {
            return fail(pos, Reason.NO_FACE);
        }
        return place(pos, bx, feetY, bz);
    }

    /**
     * 往 {@code pos} 放一块垫路料:放的那一刻身体脚在 {@code (bx, feetY, bz)}。不往身体那一刻占着的格里放。
     */
    boolean place(BlockPos pos, int bx, double feetY, int bz) {
        if (Clearance.occupies(body, Pose.STANDING, bx, feetY, bz, pos)) {
            return fail(pos, Reason.OCCUPIED);
        }
        BlockState current = getBlockState(pos);
        CostModel.Admission admission = model.admitPlace(this, pos, current);
        if (!admission.ok()) {
            return fail(pos, admission.refused(), admission.detail());
        }
        if (!Reach.reaches(body, Pose.STANDING, bx, feetY, bz, pos)) {
            return fail(pos, Reason.OUT_OF_REACH);
        }
        Block block = model.placing().orElseThrow();
        edits.add(new Edit.Place(pos.immutable(), current, block, admission.permit()));
        place(pos, block);
        return true;
    }

    /**
     * 下落摔不起时在落点 {@code pos} 倒一桶水接住(准入见 {@link CostModel#admitCatch}):草稿上这一格成了水,落点就是落进水里。
     */
    boolean catchFall(BlockPos pos) {
        BlockState current = getBlockState(pos);
        CostModel.Admission admission = model.admitCatch(this, pos, current);
        if (!admission.ok()) {
            return fail(pos, admission.refused(), admission.detail());
        }
        edits.add(new Edit.Catch(pos.immutable(), current, admission.permit()));
        pour(pos);
        return true;
    }

    /**
     * 腾出身体要进的这些格(第 0 层 {@link Clearance#blockers} 给的),自上而下:能用手开关的门就开关一下,其余挖掉——
     * {@code mayDig} 为 false 的走法不挖,遇到就以净空不足失败。已经改过的格(比如门的另一半)跳过,改完挡不挡由调用方
     * 再问几何。
     *
     * @param bx       挖的时候身体所在的列与脚高
     * @param grounded 挖的时候脚踏实地
     */
    boolean clear(List<BlockPos> blockers, boolean mayDig, int bx, double feetY, int bz, boolean grounded) {
        for (int i = blockers.size() - 1; i >= 0; i--) {
            BlockPos cell = blockers.get(i);
            if (changed(cell)) {
                continue;
            }
            BlockState state = getBlockState(cell);
            if (Semantics.openableByHand(state)) {
                edits.add(new Edit.Door(cell.immutable(), state));
                toggle(cell);
            } else if (!mayDig) {
                return fail(cell, Reason.NO_CLEARANCE);
            } else if (!dig(cell, bx, feetY, bz, grounded)) {
                return false;
            }
        }
        return true;
    }
}
