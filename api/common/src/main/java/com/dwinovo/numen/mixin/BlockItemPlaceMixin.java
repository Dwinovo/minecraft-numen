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
 * 玩家放置记录的写入口:{@code BlockItem.place} 成功返回时,把这一格连同放的人记进 {@link PlacedBlocks}——
 * 真玩家、同伴都照实记。同伴自己垫的路、搭的桥是她的,拆不拆由裁决按"是不是她自己放的"定
 * ({@code placed} 信号),这里不替谁抹记号;建造任务收工另把成果格改记到主人名下。
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
            PlacedBlocks.of(level).record(context.getClickedPos(),
                    new PlacedBlocks.Placer(player.getUUID(), player.getGameProfile().getName()));
        }
    }
}
