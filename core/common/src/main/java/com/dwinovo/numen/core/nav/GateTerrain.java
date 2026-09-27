package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Verdict;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 端口 {@link TerrainPolicy} 的实现:权限层的一份裁决快照({@link Gate},开一趟路时在主线程上取),之后搜索线程逐格问它,
 * 只读快照与传进来的视图。放行;要问——凭据是写给主人的那一条征询({@link com.dwinovo.numen.permission.ConsentItem});
 * 拒绝——理由是裁决本身({@link Verdict})。
 *
 * <p>挖是拆这一格;放是往这一格里放那块方块的物品,接住坠落倒的水是放一桶水。
 */
record GateTerrain(Gate gate) implements TerrainPolicy {

    @Override
    public Permit judge(Change change, BlockPos pos, BlockState state, BlockGetter view) {
        Action action = switch (change) {
            case Change.Dig dig -> Action.breakBlock(pos, state);
            case Change.Place place -> Action.place(pos, state, itemOf(place));
        };
        Verdict verdict = gate.judge(action, view);
        return switch (verdict.kind()) {
            case ALLOW -> Permit.ALLOW;
            case ASK -> Permit.ask(gate.consentItem(action, verdict, view));
            case DENY -> Permit.deny(verdict);
        };
    }

    /** 放的是什么物品:水方块没有物品形态,倒水用的是水桶。 */
    private static Item itemOf(Change.Place place) {
        return place.block() == Blocks.WATER ? Items.WATER_BUCKET : place.block().asItem();
    }
}
