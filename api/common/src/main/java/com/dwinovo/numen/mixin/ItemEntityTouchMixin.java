package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;

/**
 * 掉落物碰到她、原版正要把它往她背包里放的那一刻,交给 {@link NumenPlayer#touchedItem}:放不放得下由那里按原版背包的规则判。
 *
 * <p>只在原版真会去捡的时候交——拾取冷却已过、这件东西没指名给别人({@code ItemEntity.playerTouch} 自己的两个条件)。挂在方法开头:
 * 原版放不下时什么都不做就返回,开头是唯一看得见"碰到了却没放进去"的地方。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityTouchMixin {

    @Shadow
    private int pickupDelay;

    @Shadow
    private UUID target;

    @Inject(method = "playerTouch", at = @At("HEAD"))
    private void numen$touched(Player player, CallbackInfo ci) {
        if (player instanceof NumenPlayer her && pickupDelay == 0 && (target == null || target.equals(her.getUUID()))) {
            her.touchedItem((ItemEntity) (Object) this);
        }
    }
}
