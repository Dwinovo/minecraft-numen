package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * GameTest 里让她进世界的几个写法:走的是生产里同一条路({@link CompanionFactory#spawn} 等),调用返回时她已经在世界里。
 */
public final class Bodies {

    private Bodies() {}

    /** 登记里没有这一条也照样进(不经名册的测试用身体)。 */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String label, UUID ownerUuid,
                                    ServerLevel level, Vec3 pos) {
        return CompanionFactory.spawn(server, companionUuid, compliant(label), ownerUuid, level, pos);
    }

    /**
     * 用例给身体起的名字是写给人看的标签(如 {@code gametest_cartographer}),常常超过产品的名字规则(3 到 16 位字母、数字、下划线,
     * {@link MojangSkins#validName})。真身体的名字走的是真玩家的登录规矩,
     * 所以造身体的这一处把不合规的标签收成合规的名字:同一个标签永远得到同一个名字,用例要名字就从身体上取。
     */
    public static String compliant(String label) {
        if (MojangSkins.validName(label)) {
            return label;
        }
        return "gt_" + Integer.toString(label.hashCode() & 0x7fffffff, 36);
    }

    /** 召唤(名册里没有就新建,有就叫醒)。 */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String label, ServerLevel level, Vec3 pos) {
        return Companions.summon(server, ownerUuid, compliant(label), level, pos);
    }

    /** 把名册里的她叫回世界;名册不认得她是 {@code null}。 */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        return Companions.respawn(server, companionUuid);
    }

    /** 玩家召唤的入口;拒绝的话在 {@code problem} 里。 */
    public static Companions.Summoned summonFor(ServerPlayer owner, String name,
                                                com.dwinovo.numen.api.entity.SummonSetup setup) {
        return Companions.summonFor(owner, name, setup);
    }
}
