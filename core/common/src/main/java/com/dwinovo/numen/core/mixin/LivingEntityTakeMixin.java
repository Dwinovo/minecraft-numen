package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.act.Drops;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版每一次捡东西(玩家、会捡东西的生物)都经 {@code take}:捡的是谁交给 {@link Drops}。NeoForge 的拾取事件只管玩家、Fabric
 * 没有,common 不按加载器分支,挂在两边共有的这一处。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityTakeMixin {

    @Inject(method = "take", at = @At("HEAD"))
    private void numen$taking(Entity entity, int amount, CallbackInfo ci) {
        Drops.taken(entity, (LivingEntity) (Object) this);
    }
}
