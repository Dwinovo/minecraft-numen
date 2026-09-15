package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.PlacedBlocks;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 玩家放置记录的唯一写入口:{@code BlockItem.place} 成功返回时,放的人是真玩家就把这一格记进
 * {@link PlacedBlocks}(连同是谁),是同伴就把这一格的旧记号抹掉——她垫的路、她盖的墙是她自己的动作,
 * 不该被当成别人留下的东西;建造任务收工另把成果格登记回来。
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
        Player who = context.getPlayer();
        if (who instanceof NumenPlayer) {
            PlacedBlocks.of(level).forget(context.getClickedPos());
        } else if (who instanceof ServerPlayer player) {
            PlacedBlocks.of(level).record(context.getClickedPos(),
                    new PlacedBlocks.Placer(player.getUUID(), player.getGameProfile().getName()));
        }
    }
}
