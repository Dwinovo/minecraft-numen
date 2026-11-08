package com.dwinovo.numen.api.mixin;

import com.dwinovo.numen.api.mixin.inject.BoatAccessor;
import com.dwinovo.numen.api.platform.services.IBoatPhysics;

import net.minecraft.world.entity.vehicle.Boat;

/**
 * {@link IBoatPhysics} 的模组实现:经 {@link BoatAccessor} 调原版私有的 {@code controlBoat}。
 * Mixin 配置的包是下一层的 {@code mixin.inject},这里不是 Mixin 声明的包,可以被 ServiceLoader 正常加载。
 */
public final class BoatPhysicsImpl implements IBoatPhysics {

    @Override
    public void paddle(Boat boat) {
        ((BoatAccessor) boat).numen$controlBoat();
    }
}
