package com.dwinovo.numen.pathing.plan;

import com.dwinovo.numen.pathing.world.Bounds;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.pathing.world.Semantics.Kind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.GameMasterBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.InfestedBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * 物理上能不能挖一格,全模块只此一处。许不许挖(路线规格、许可)不在这里,由 {@link CostModel#admitDig} 在它前后问。
 *
 * <p>挖不了的:世界边界与建筑高度之外;冒险、旁观模式;基岩这类硬度为负的(创造模式除外)与命令方块这类要管理员的;
 * 冰(生存模式挖掉后下面有东西就化成水);虫蚀方块(生存模式挖了钻出蠹虫)。挖了会出事的:
 * <ul>
 *   <li><b>漏液</b>——这一格自己含水(挖掉后水留在原地往外流);正上方有任何液体(往下灌);四个水平邻格有液体源头、
 *       会横着流过来的流动液体、或含水的方块。规格要求从严({@code strictLiquidCheck})时,水平邻格有任何液体都算。</li>
 *   <li><b>塌方</b>——正上方是落沙(挖空后它掉下来);水平邻格是下面已经空着的落沙(挖这一格的方块更新会让它掉下去)。</li>
 * </ul>
 * 一步里要挖好几格时,调用方按执行的先后在叠了前面改动的视图上逐格问,"上面那格已经挖空"这种情况自然算进去。
 */
public final class DigRules {

    private DigRules() {}

    /** 物理上能挖返回 null,否则返回挖不了或挖了会出事的原因。 */
    public static Reason check(WorldView view, BodySnapshot body, BlockPos pos, BlockState state, boolean strictLiquid) {
        if (!Bounds.allowsEdit(view.border(), view, pos)) {
            return Reason.OUT_OF_BOUNDS;
        }
        if (!body.mayEdit()) {
            return Reason.EDIT_RESTRICTED;
        }
        Block block = state.getBlock();
        if (block instanceof GameMasterBlock || (state.getDestroySpeed(view, pos) < 0 && !body.creative())) {
            return Reason.UNBREAKABLE;
        }
        if (!body.creative()) {
            if (block instanceof IceBlock && melts(view, pos)) {
                return Reason.MELTS;
            }
            if (block instanceof InfestedBlock) {
                return Reason.INFESTED;
            }
        }
        if (!state.getFluidState().isEmpty() || !view.getFluidState(pos.above()).isEmpty()) {
            return Reason.WOULD_FLOOD;
        }
        if (Semantics.is(view, pos.above(), Kind.FALLING)) {
            return Reason.WOULD_COLLAPSE;
        }
        for (Direction side : Direction.Plane.HORIZONTAL) {
            BlockPos neighbor = pos.relative(side);
            if (flowsIn(view, neighbor, strictLiquid)) {
                return Reason.WOULD_FLOOD;
            }
            if (view.getBlockState(neighbor).getBlock() instanceof FallingBlock
                    && FallingBlock.isFree(view.getBlockState(neighbor.below()))) {
                return Reason.WOULD_COLLAPSE;
            }
        }
        return null;
    }

    /** 原版 {@code IceBlock.playerDestroy}:下面挡得住移动或是液体时,冰挖掉留下一格水。原版用的就是这两个方法。 */
    @SuppressWarnings("deprecation")
    private static boolean melts(WorldView view, BlockPos pos) {
        BlockState below = view.getBlockState(pos.below());
        return below.blocksMotion() || below.liquid();
    }

    /** 水平邻格的液体会不会流进挖空的格。 */
    private static boolean flowsIn(WorldView view, BlockPos neighbor, boolean strict) {
        BlockState state = view.getBlockState(neighbor);
        FluidState fluid = state.getFluidState();
        if (fluid.isEmpty()) {
            return false;
        }
        if (strict || !(state.getBlock() instanceof LiquidBlock) || fluid.isSource()) {
            // 含水的方块与液体源头都会往空格里横着流
            return true;
        }
        // 流动的液体下面还是液体时,它往下落,不横着流过来
        return !(view.getBlockState(neighbor.below()).getBlock() instanceof LiquidBlock);
    }
}
