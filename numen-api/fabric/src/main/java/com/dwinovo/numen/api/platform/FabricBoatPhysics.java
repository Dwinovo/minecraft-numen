package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.mixin.BoatAccessor;
import com.dwinovo.numen.api.platform.services.IBoatPhysics;

import net.minecraft.world.entity.vehicle.Boat;

/**
 * {@link IBoatPhysics} 的模组实现:经 {@link BoatAccessor} 调原版私有的 {@code controlBoat}。
 * 不能放进 {@code mixin} 包:Mixin 不许直接加载包里的非 Mixin 类。
 */
public final class FabricBoatPhysics implements IBoatPhysics {

    @Override
    public void paddle(Boat boat) {
        ((BoatAccessor) boat).numen$controlBoat();
    }
}
