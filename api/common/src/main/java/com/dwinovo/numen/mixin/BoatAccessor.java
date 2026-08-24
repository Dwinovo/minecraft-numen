package com.dwinovo.numen.mixin;

import net.minecraft.world.entity.vehicle.AbstractBoat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 船的桨物理入口。原版把 {@code controlBoat}(输入 → 转向 → 前进加速 → 划桨
 * 动画)锁在客户端分支里;同伴的驾驶在服务端,每刻 {@code setInput} 后从这里
 * 调同一段原版数学——不复制一个常数,版本升级时物理永远跟官方走。
 *
 * <p>1.21.2+ 船族拆层级:输入字段与 {@code controlBoat} 都住在 {@code AbstractBoat}
 * (木船/木筏/箱船共此一段物理),mixin 目标随之上移。
 */
@Mixin(AbstractBoat.class)
public interface BoatAccessor {

    @Invoker("controlBoat")
    void numen$controlBoat();
}
