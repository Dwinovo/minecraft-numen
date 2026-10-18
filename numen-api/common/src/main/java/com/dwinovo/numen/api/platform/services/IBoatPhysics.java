package com.dwinovo.numen.api.platform.services;

import net.minecraft.world.entity.vehicle.Boat;

/**
 * 船的桨物理。原版把 {@code controlBoat}(输入 → 转向 → 前进加速 → 划桨动画)锁成私有、只在客户端分支里调;
 * 同伴的驾驶在服务端,所以每刻 {@code setInput} 之后要从这里调一次同一段原版数学——不复制常数,物理跟着官方走。
 * 私有成员怎么够得着是平台的事(模组用 Mixin 调用器)。
 */
public interface IBoatPhysics {

    /** 按船当前的输入键推进一刻:转向、加速、划桨动画,和真玩家按着 WASD 时客户端那一趟完全一样。 */
    void paddle(Boat boat);
}
