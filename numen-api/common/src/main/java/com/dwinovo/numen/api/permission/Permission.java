package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.permission.Settings;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 权限层的唯一入口。全仓凡会改世界的地方——她的鼠标、直接写一格、扔东西、执行命令、成本模型、选目标——只问这里;
 * 能不能挖/放/打没有第二个出处。
 *
 * <p>两种问法:{@link #judge(NumenPlayer, Action)} 在主线程对活世界问一个动作;
 * {@link #gateFor} 在主线程取一份快照,交给搜索线程逐格问(见 {@link Gate#judge})。
 * 模型不是权限的执行者:它没有任何写入口,看到的只是普通的工具结果和回执。
 */
public final class Permission {

    private Permission() {}

    /**
     * 主线程:取这只同伴此刻的裁决快照——主人给她定的设置、她身边的方块归属、主人答应下来的任务期授权。
     * 快照不可变,任何线程可读。
     */
    public static Gate gateFor(NumenPlayer companion) {
        ServerLevel level = (ServerLevel) companion.level();
        UUID owner = companion.keeperUuid();
        Actor actor = new Actor(companion.getUUID(), companion.getName().getString(), owner, siblingsOf(companion));
        return new Gate(actor, settingsOf(companion), Owners.around(level, companion.blockPosition(), OWNER_RADIUS_CHUNKS),
                ConsentDesk.of(companion).granted());
    }

    /** 取方块归属抄多大一片:她身边这么多个区块。 */
    public static final int OWNER_RADIUS_CHUNKS = 10;

    /** 主人名下此刻在游戏里的别的同伴;还没有主人是空集。 */
    private static Set<UUID> siblingsOf(NumenPlayer companion) {
        UUID owner = companion.keeperUuid();
        Set<UUID> siblings = new HashSet<>();
        if (owner != null) {
            for (ServerPlayer body : companion.getServer().getPlayerList().getPlayers()) {
                if (body != companion && body instanceof NumenPlayer other && other.isOwnedByPlayer(owner)) {
                    siblings.add(other.getUUID());
                }
            }
        }
        return siblings;
    }

    /** 主线程:对活世界裁决一个动作。 */
    public static Verdict judge(NumenPlayer companion, Action action) {
        return gateFor(companion).judgeLive(action, (ServerLevel) companion.level());
    }

    /** 主人给这只同伴定的设置;还没有主人时是出厂的。 */
    public static Settings settingsOf(NumenPlayer companion) {
        UUID owner = companion.keeperUuid();
        if (owner == null) {
            return Settings.FACTORY;
        }
        return PermissionStore.of(companion.getServer(), owner).settingsOf(companion.getUUID());
    }
}
