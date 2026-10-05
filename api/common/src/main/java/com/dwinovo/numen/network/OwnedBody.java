package com.dwinovo.numen.network;

import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 上行的包点名要用的那具身体:哪个包都按同一套校验认它,认不出就说为什么。
 *
 * <ol>
 *   <li>在所有维度里找(同伴可能在下界干活,主人在主世界等);没加载就把休眠的唤起来;</li>
 *   <li>发包的必须是它的主人(UUID 比较,跨维度也成立)。</li>
 * </ol>
 * 故意不查主人离它多远:区块票让她在远处照常干活,动作在<em>她</em>的位置、以她自己的能力执行,主人的距离不授予任何东西。
 */
public final class OwnedBody {

    private OwnedBody() {}

    /**
     * @param refuse 认不出时收一句给模型看的原因
     * @return 这具身体;认不出是 null
     */
    public static NumenPlayer of(ServerPlayer sender, UUID entityUuid, Consumer<String> refuse) {
        MinecraftServer server = sender.level().getServer();
        NumenPlayer companion = NumenPlayer.findByUuid(server, entityUuid);
        if (companion == null) {
            // 她死着的时候不许从这儿把她拉起来:复活的唯一出口是定时复活
            // (Companions.tickRespawns,读注册表的 diedAt)。两条复活路各自不知道对方,
            // 结果是一次死亡复活出两具同 UUID 的身体同时在玩家列表里 —— 排程器会在
            // 两具之间无限重建大脑,每次还重放一遍她手上的活。
            CompanionRegistry.Entry reg = CompanionRegistry.get(server).find(entityUuid);
            if (reg != null && reg.diedAt() > 0L) {
                refuse.accept("她刚死了,正在复活途中——等复活事件到了再派");
                return null;
            }
            companion = Companions.respawn(server, entityUuid);
        }
        if (companion == null) {
            refuse.accept("companion not found (never summoned, or its data is gone)");
            return null;
        }
        if (!companion.isOwnedByPlayer(sender.getUUID())) {
            refuse.accept("not the owner");
            return null;
        }
        return companion;
    }
}
