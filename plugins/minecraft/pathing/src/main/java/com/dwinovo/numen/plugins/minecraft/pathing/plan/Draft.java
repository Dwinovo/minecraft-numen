package com.dwinovo.numen.plugins.minecraft.pathing.plan;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.plugins.minecraft.pathing.world.BodyStats;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Clearance;
import com.dwinovo.numen.api.entity.Reach;
import com.dwinovo.numen.plugins.minecraft.pathing.world.Semantics;

import net.minecraft.core.BlockPos;
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
    /** 这一步的改动;一件也没有时不建(绝大多数前提判断一件也不改)。 */
    private List<Edit> edits;
    private Premise.Fails failure;

    Draft(CostModel model, WorldView base) {
        super(base);
        this.model = model;
        this.body = model.body().stats();
    }

    // ==================== 结果 ====================

    List<Edit> edits() {
        return edits == null ? List.of() : edits;
    }

    private void record(Edit edit) {
        if (edits == null) {
            edits = new ArrayList<>(2);
        }
        edits.add(edit);
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
     * 挖掉 {@code pos}:身体此刻脚在 {@code (bx, feetY, bz)} 站着挖,手够得着它({@link Reach})就挖得到,中间隔着什么不问。
     *
     * @param grounded 挖的时候脚踏实地
     */
    boolean dig(BlockPos pos, int bx, double feetY, int bz, boolean grounded) {
        BlockState state = getBlockState(pos);
        CostModel.Admission admission = model.admitDig(this, pos, state);
        if (!admission.ok()) {
            return fail(pos, admission.refused(), admission.detail());
        }
        if (!reach(pos, bx, feetY, bz)) {
            return false;
        }
        boolean eyeInWater = Semantics.eyeInWater(this, bx + 0.5, feetY + body.eyeHeight(), bz + 0.5);
        record(new Edit.Dig(pos.immutable(), state, admission.permit(), eyeInWater, grounded));
        dig(pos);
        return true;
    }

    /** 身体脚在 {@code (bx, feetY, bz)} 时手够不够得着 {@code pos};够不着记 {@link Reason#OUT_OF_REACH}。 */
    private boolean reach(BlockPos pos, int bx, double feetY, int bz) {
        return Reach.reaches(body.eye(bx, feetY, bz), pos, body.blockReach()) || fail(pos, Reason.OUT_OF_REACH);
    }

    /**
     * 往 {@code pos} 放一块垫路料:放的那一刻身体脚在 {@code (bx, feetY, bz)},手够得着这一格({@link Reach})、有一个邻格的面可贴
     * (准入里问)就放得下。不往身体那一刻占着的格里放。
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
        if (!reach(pos, bx, feetY, bz)) {
            return false;
        }
        Block block = model.placing();
        record(new Edit.Place(pos.immutable(), current, block, admission.permit()));
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
        record(new Edit.Catch(pos.immutable(), current, admission.permit()));
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
                if (!reach(cell, bx, feetY, bz)) {
                    return false;
                }
                record(new Edit.Door(cell.immutable(), state));
                toggle(cell, state);
                if (!Clearance.fits(this, body, bx, feetY, bz)) {
                    // 翻过来的门板落进了身体此刻站着的这一格(开着的活板门关上,门板压在头顶):身体就卡在原地
                    return fail(cell, Reason.NO_CLEARANCE);
                }
            } else if (!mayDig) {
                return fail(cell, Reason.NO_CLEARANCE);
            } else if (!dig(cell, bx, feetY, bz, grounded)) {
                return false;
            }
        }
        return true;
    }
}
