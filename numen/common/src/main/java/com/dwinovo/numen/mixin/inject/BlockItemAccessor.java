package com.dwinovo.numen.mixin.inject;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 方块物品在这次点击下会放成什么状态:{@code BlockItem.place} 里决定状态的那一步({@code getPlacementState},连带"站得住、没被
 * 挡着"的判断),只读、不动世界。它是 protected,而各物品还会覆写它(火把分立地与贴墙、脚手架……),所以只能经物品自己问。
 */
@Mixin(BlockItem.class)
public interface BlockItemAccessor {

    @Invoker("getPlacementState")
    BlockState numen$placementState(BlockPlaceContext context);
}
