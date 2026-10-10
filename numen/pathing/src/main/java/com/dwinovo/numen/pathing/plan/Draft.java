package com.dwinovo.numen.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Clearance;
import com.dwinovo.numen.api.entity.Faces;
import com.dwinovo.numen.api.entity.Reach;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Stepping;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

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
        if (!Reach.reaches(body.eye(bx, feetY, bz), pos, body.blockReach())) {
            return fail(pos, Reason.OUT_OF_REACH);
        }
        boolean eyeInWater = Semantics.eyeInWater(this, bx + 0.5, feetY + body.eyeHeight(), bz + 0.5);
        edits.add(new Edit.Dig(pos.immutable(), state, admission.permit(), eyeInWater, grounded));
        dig(pos);
        return true;
    }

    /** 潜行探出边沿到头时眼睛的位置:列中心往 {@code heading} 方向挪执行有把握探出的那么远({@link Stepping#leanOut})。 */
    private Vec3 leanedEye(int bx, double feetY, int bz, Heading heading) {
        double out = Stepping.leanOut(this, body, bx, feetY, bz, heading.dx(), heading.dz());
        return body.eye(bx, feetY, bz).add(heading.dx() * out, 0, heading.dz() * out);
    }

    /** 搭桥的姿势:站定点得中,或要潜行探出边沿才点得中。 */
    enum Bridging {
        STANDING, LEANING
    }

    /**
     * 桥位 {@code pos} 上那一块垫路料({@link #place} 放的)是站在 {@code (bx, feetY, bz)} 那一列、往 {@code heading} 方向走过去时放的:
     * 在这一步别的改动都做完、桥块还没放(桥位暂时还原成放之前的样子)的世界里,先看站定、眼睛在列中心能不能点中一个面({@link Faces#inSight}),点不中再看
     * 潜行探出边沿(探出多远见 {@link Stepping#leanOut}:执行有把握到达的位置,不是最远处)能不能。两种都点不中就没法放,记 {@link Reason#NO_FACE}。判据只此一处:执行时站着瞄、
     * 或潜行探出去瞄,瞄的就是这里判过的那个姿势下的面。桥块的改动挪到改动表的末尾——执行是先腾出要探进去的格、再放。
     *
     * @return 放得下用哪种姿势;放不下为 null(失败已记)
     */
    Bridging bridging(BlockPos pos, int bx, double feetY, int bz, Heading heading) {
        Edit.Place bridge = null;
        for (Edit edit : edits) {
            if (edit instanceof Edit.Place place && place.pos().equals(pos)) {
                bridge = place;
            }
        }
        BlockState placed = getBlockState(pos);
        set(pos, bridge.replaced());
        Bridging how = Bridging.STANDING;
        try {
            if (Faces.inSight(this, body.eye(bx, feetY, bz), body.blockReach(), pos, bridge.block()) == null) {
                if (Faces.inSight(this, leanedEye(bx, feetY, bz, heading), body.blockReach(), pos,
                        bridge.block()) == null) {
                    fail(pos, Reason.NO_FACE);
                    return null;
                }
                how = Bridging.LEANING;
            }
        } finally {
            set(pos, placed);
        }
        edits.remove(bridge);
        edits.add(bridge);
        return how;
    }

    /**
     * 同 {@link #place},另要站在 {@code (bx, feetY, bz)} 那一列中心的眼睛点得中一个面({@link Faces#inSight}):先站定再放的走法
     * (上一级垫一块台阶)执行时就是站在那儿瞄这个面,点不中就放不下。
     */
    boolean placeInSight(BlockPos pos, int bx, double feetY, int bz) {
        Block block = model.placing().orElse(null);
        if (block != null && Faces.inSight(this, body.eye(bx, feetY, bz), body.blockReach(), pos,
                block) == null) {
            return fail(pos, Reason.NO_FACE);
        }
        return place(pos, bx, feetY, bz);
    }

    /**
     * 往 {@code pos} 放一块垫路料:放的那一刻身体脚在 {@code (bx, feetY, bz)}。不往身体那一刻占着的格里放。
     */
    boolean place(BlockPos pos, int bx, double feetY, int bz) {
        if (Clearance.occupies(body, bx, feetY, bz, pos)) {
            return fail(pos, Reason.OCCUPIED);
        }
        BlockState current = getBlockState(pos);
        CostModel.Admission admission = model.admitPlace(this, pos, current);
        if (!admission.ok()) {
            return fail(pos, admission.refused(), admission.detail());
        }
        if (!Reach.reaches(body.eye(bx, feetY, bz), pos, body.blockReach())) {
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
