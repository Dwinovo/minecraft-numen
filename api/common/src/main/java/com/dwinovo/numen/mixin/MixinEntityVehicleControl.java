package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 载具的服务端权威开关。原版把"谁驱动这个载具"交给控制乘客的客户端:船的
 * {@code tick} 在控制者不是本地实例时每刻 {@code setDeltaMovement(ZERO)},
 * 等一个客户端移动包——而同伴没有客户端,那个包永远不来,船就冻死在原地。
 * 控制乘客是同伴时判服务端为权威,原版的浮力、摩擦、移动整条物理链在服务端
 * 活过来,骑乘生物(马)的 {@code travelRidden} 同理。与 Carpet 假玩家的
 * EntityMixin 同一做法。
 *
 * <p>1.21.5 把 {@code isControlledByLocalInstance} 改名成
 * {@code isLocalInstanceAuthoritative} 并拆出"客户端权威"({@code isClientAuthoritative},
 * 控制乘客是玩家即真——同伴是 Player 子类,也会中招,冻船 bug 原样存在);
 * 注入点跟着改名,覆写语义一字不变:控制者是同伴时,服务端即权威。
 */
@Mixin(Entity.class)
public abstract class MixinEntityVehicleControl {

    @Shadow
    public abstract LivingEntity getControllingPassenger();

    @Inject(method = "isLocalInstanceAuthoritative", at = @At("HEAD"), cancellable = true)
    private void numen$serverDrivesCompanionVehicles(CallbackInfoReturnable<Boolean> cir) {
        if (getControllingPassenger() instanceof NumenPlayer) {
            cir.setReturnValue(!((Entity) (Object) this).level().isClientSide());
        }
    }
}
