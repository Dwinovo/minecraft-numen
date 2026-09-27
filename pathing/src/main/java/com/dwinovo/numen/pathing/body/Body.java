package com.dwinovo.numen.pathing.body;

import com.dwinovo.numen.pathing.plan.BodySnapshot;

import net.minecraft.server.level.ServerPlayer;

/**
 * 端口:要驱动的身体。模块从这里拿到身体本身(按键、视角、手都落在它上面)与它此刻的身体快照。
 *
 * <p>身体得是一具每刻跑 {@link Physics#step} 的服务端玩家:假玩家没有客户端,物理步进由宿主在身体自己的实体刻里补上。
 * 快照的原版口径是 {@link Snapshots#of};宿主要在它上面收紧(比如自己的设置不许动背包深处)就在这里给。
 */
public interface Body {

    ServerPlayer entity();

    /** 此刻的身体快照;每次规划、每一步复核前都取一次。 */
    BodySnapshot snapshot();

    /** 照原版口径的身体。 */
    static Body of(ServerPlayer entity) {
        return new Body() {
            @Override
            public ServerPlayer entity() {
                return entity;
            }

            @Override
            public BodySnapshot snapshot() {
                return Snapshots.of(entity);
            }
        };
    }
}
