package com.dwinovo.numen.api.mixin.inject;

import net.minecraft.world.entity.vehicle.AbstractBoat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * 船的桨物理入口。原版把 {@code controlBoat}(输入 → 转向 → 前进加速 → 划桨
 * 动画)锁在客户端分支里;同伴的驾驶在服务端,每刻 {@code setInput} 后从这里
 * 调同一段原版数学——不复制一个常数,版本升级时物理永远跟官方走。
 *
 * <p>目标是 {@code AbstractBoat}:1.21.2 起桨物理与 {@code controlBoat} 住在这一层,
 * 木船、木筏、箱船都继承它。
 */
@Mixin(AbstractBoat.class)
public interface BoatAccessor {

    @Invoker("controlBoat")
    void numen$controlBoat();
}
