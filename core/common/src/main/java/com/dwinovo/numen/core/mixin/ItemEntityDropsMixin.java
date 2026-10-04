package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.act.Drops;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 掉落物的两件事交给 {@link Drops}:同种的两堆并成一堆(并过去几件),挨打挨到没了(是什么打的)。原版与两个加载器都没有这两件事的
 * 事件;不跟并堆,并进别的一堆的会被当成不见了,不记伤害来源,就说不出是岩浆、火还是仙人掌毁的。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityDropsMixin {

    @WrapMethod(method = "merge(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;"
            + "Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/ItemStack;)V")
    private static void numen$followMerge(ItemEntity into, ItemStack intoStack, ItemEntity from, ItemStack fromStack,
                                          Operation<Void> original) {
        int before = fromStack.getCount();
        original.call(into, intoStack, from, fromStack);
        Drops.merged(into, from, before - fromStack.getCount());
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void numen$destroyed(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Drops.hurt((ItemEntity) (Object) this, source);
    }
}
