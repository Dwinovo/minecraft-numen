package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.IBoatPhysics;
import net.minecraft.world.entity.vehicle.Boat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Paper 上的 {@link IBoatPhysics}:Paper 的服务端是 Mojang 名,按名字找私有的 {@code controlBoat},方法句柄一次查好缓存。
 * 找不到(换了版本改了名)是类加载时就抛的错,不是等到有人划船才发现。
 */
public final class PaperBoatPhysics implements IBoatPhysics {

    private static final MethodHandle CONTROL_BOAT;

    static {
        try {
            CONTROL_BOAT = MethodHandles.privateLookupIn(Boat.class, MethodHandles.lookup())
                    .findVirtual(Boat.class, "controlBoat", MethodType.methodType(void.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @Override
    public void paddle(Boat boat) {
        try {
            CONTROL_BOAT.invokeExact(boat);
        } catch (Throwable t) {
            throw new IllegalStateException("Boat.controlBoat 抛了异常", t);
        }
    }
}
