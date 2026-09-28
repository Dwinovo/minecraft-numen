package com.dwinovo.numen.pathing.body;

import com.dwinovo.numen.pathing.plan.BodySnapshot;

import net.minecraft.server.level.ServerPlayer;

/**
 * 端口:要驱动的身体。模块从这里拿到身体本身(视角、手都落在它上面)、它的键盘与它此刻的身体快照。
 *
 * <p>身体得是一具每刻在自己的实体刻里跑 {@link Physics#step} 的服务端玩家:假玩家没有客户端,按键落成输入、物理步进都由
 * 宿主在那里补上。键盘一具身体只有一副,谁要动这具身体(导航、宿主自己的本能与动作)都按同一副,每刻只落一次。
 * 快照的原版口径是 {@link Snapshots#of};宿主要在它上面收紧(比如自己的设置不许动背包深处)就在这里给。
 */
public interface Body {

    ServerPlayer entity();

    /** 这具身体的键盘。 */
    Controls controls();

    /** 此刻的身体快照;每次规划、每一步复核前都取一次。 */
    default BodySnapshot snapshot() {
        return Snapshots.of(entity());
    }
}
