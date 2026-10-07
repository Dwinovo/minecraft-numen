package com.dwinovo.numen.mixin;

import com.dwinovo.numen.permission.PlacedBlocks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 经物品落位的放置记录:{@code BlockItem.place} 成功返回时,交给 {@link PlacedBlocks#placedBy} 记下这一格与放的人——
 * 真玩家、同伴都照实记。同伴自己垫的路、搭的桥、砌的墙记在她自己名下,拆不拆由规则按 {@code self_placed} 与
 * {@code placed} 两个信号定,这里不替谁改记号。
 *
 * <p>挂在 {@code place} 而不是 {@code useOn}:所有经物品落位的方块(含模组的)都过这一处,
 * 命令、活塞、生长写下的不过——那些本来就不是"玩家放的"。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemPlaceMixin {

    @Inject(method = "place(Lnet/minecraft/world/item/context/BlockPlaceContext;)Lnet/minecraft/world/InteractionResult;",
            at = @At("RETURN"))
    private void numen$recordPlacement(BlockPlaceContext context, CallbackInfoReturnable<InteractionResult> cir) {
        if (!cir.getReturnValue().consumesAction()) {
            return;
        }
        if (!(context.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (context.getPlayer() instanceof ServerPlayer player) {
            PlacedBlocks.placedBy(level, context.getClickedPos(), player);
        }
    }
}
