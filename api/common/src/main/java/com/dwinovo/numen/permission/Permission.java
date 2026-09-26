package com.dwinovo.numen.permission;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * 权限层的唯一入口。全仓凡会改世界的地方——挖掘落点、放置落点、攻击落点、成本模型、
 * 选目标——只问这里;能不能挖/放/打没有第二个出处。
 *
 * <p>两种问法:{@link #judge(NumenPlayer, Action)} 在主线程对活世界问一个动作;
 * {@link #gateFor} 在主线程取一份快照,交给搜索线程逐格问(见 {@link Gate#judge})。
 * 模型不是权限的执行者:它没有任何写入口,看到的只是普通的工具结果和回执。
 */
public final class Permission {

    private Permission() {}

    /**
     * 主线程:取这只同伴此刻的裁决快照——模式、主人层与出厂层规则、所在维度的放置记录、主人答应
     * 下来的任务期授权。快照不可变,任何线程可读。
     */
    public static Gate gateFor(NumenPlayer companion) {
        ServerLevel level = (ServerLevel) companion.level();
        return new Gate(companion.getUUID(), modeOf(companion), ownerRules(companion), RuleSet.factory(),
                PlacedBlocks.of(level), ConsentDesk.of(companion).granted());
    }

    /** 主线程:对活世界裁决一个动作。 */
    public static Verdict judge(NumenPlayer companion, Action action) {
        return gateFor(companion).judgeLive(action, (ServerLevel) companion.level());
    }

    /** 这只同伴的模式;还没有主人时按 {@link Mode#ASK}。 */
    public static Mode modeOf(NumenPlayer companion) {
        UUID owner = companion.getOwnerUuid();
        if (owner == null) {
            return Mode.ASK;
        }
        return PermissionStore.of(companion.getServer(), owner).modeOf(companion.getUUID());
    }

    /** 主人给这只同伴设模式(面板与命令的落点);还没有主人时不存。 */
    public static void setMode(NumenPlayer companion, Mode mode) {
        UUID owner = companion.getOwnerUuid();
        if (owner == null) {
            return;
        }
        PermissionStore.of(companion.getServer(), owner).setMode(companion.getUUID(), mode);
    }

    /** 这只同伴的主人写的那一层规则;还没有主人时是空层。 */
    private static RuleSet ownerRules(NumenPlayer companion) {
        UUID owner = companion.getOwnerUuid();
        return owner == null ? RuleSet.EMPTY : PermissionStore.of(companion.getServer(), owner).rules();
    }
}
