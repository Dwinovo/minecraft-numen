package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.act.Drops;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 掉落物的两件事交给 {@link Drops}:同种的两堆并成一堆(并过去几件),挨打挨到没了(是什么打的)。原版与两个加载器都没有这两件事的
 * 事件;不跟并堆,并进别的一堆的会被当成不见了,不记伤害来源,就说不出是岩浆、火还是仙人掌毁的。
 *
 * <p>并堆挂在 {@code tryToMerge}(两堆碰面的那一趟)的头尾:这一代没有包住整个方法的注入,拿不到并堆那个静态方法前后的件数,
 * 而两只实体各自的件数在这一趟前后看得见——少了的是并过去的那一只。
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityDropsMixin {

    /** 这一趟开始时这一堆的件数。每只实体只由一个线程刻它,所以存在实体自己身上。 */
    @Unique
    private int numen$countBefore;
    /** 这一趟开始时对面那一堆的件数。 */
    @Unique
    private int numen$otherBefore;

    @Inject(method = "tryToMerge", at = @At("HEAD"))
    private void numen$beforeMerge(ItemEntity other, CallbackInfo ci) {
        numen$countBefore = ((ItemEntity) (Object) this).getItem().getCount();
        numen$otherBefore = other.getItem().getCount();
    }

    @Inject(method = "tryToMerge", at = @At("RETURN"))
    private void numen$followMerge(ItemEntity other, CallbackInfo ci) {
        ItemEntity self = (ItemEntity) (Object) this;
        int selfMoved = numen$countBefore - self.getItem().getCount();
        int otherMoved = numen$otherBefore - other.getItem().getCount();
        if (selfMoved > 0) {
            Drops.merged(other, self, selfMoved);
        } else if (otherMoved > 0) {
            Drops.merged(self, other, otherMoved);
        }
    }

    @Inject(method = "hurt", at = @At("RETURN"))
    private void numen$destroyed(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        Drops.hurt((ItemEntity) (Object) this, source);
    }
}
