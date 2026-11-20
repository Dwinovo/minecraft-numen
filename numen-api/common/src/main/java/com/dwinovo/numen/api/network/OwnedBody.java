package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * 上行的包点名要用的那具身体:哪个包都按同一套校验认它,认不出就说为什么。
 *
 * <ol>
 *   <li>在所有维度里找(同伴可能在下界干活,主人在主世界等);没加载就把休眠的唤起来;</li>
 *   <li>发包的必须在它的主人表上(UUID 比较,跨维度也成立)。</li>
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
        return of(sender.level().getServer(), sender.getUUID(), entityUuid, refuse);
    }

    /** 同上,不是玩家发的包而是服务端大脑替这位主人办事:{@code ownerUuid} 是该在她主人表上的那一位。 */
    public static NumenPlayer of(MinecraftServer server, UUID ownerUuid, UUID entityUuid, Consumer<String> refuse) {
        NumenPlayer companion = NumenPlayer.findByUuid(server, entityUuid);
        if (companion == null) {
            // 该在线的她由对账带回来(登入的那一刻还没轮到推迟的对账时,这一问就是它的时机);留在外面的回来只能是主人再召唤
            // (Companions.summon),不是一个包把她拉起来
            Companions.reconcileCompanion(server, entityUuid);
            companion = NumenPlayer.findByUuid(server, entityUuid);
            CompanionRegistry.Entry reg = CompanionRegistry.get(server).find(entityUuid);
            if (companion == null && reg != null) {
                if (reg.outside().server()) {
                    refuse.accept("she was removed from the server (" + reg.removedFor() + "); she only comes back when her owner summons her again");
                    return null;
                }
                if (reg.outside().isOutside()) {
                    refuse.accept("she is offline (her owner sent her offline); she only comes back when her owner summons her again");
                    return null;
                }
                if (CompanionFactory.isArriving(entityUuid)) {
                    refuse.accept("she is on her way back into the world (the platform is still admitting her) — wait a moment and send it again");
                    return null;
                }
            }
        }
        if (companion == null) {
            refuse.accept("companion not found (never summoned, or its data is gone)");
            return null;
        }
        if (!companion.usableBy(ownerUuid)) {
            refuse.accept("not one of her owners");
            return null;
        }
        return companion;
    }
}
