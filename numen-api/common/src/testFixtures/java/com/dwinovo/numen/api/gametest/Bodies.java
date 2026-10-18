package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.entity.Arrivals;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;

/**
 * GameTest 里让她进世界的几个写法:走的是生产里同一条带回调的路({@link CompanionFactory#spawn} 等),只是把结果收下来。
 * GameTest 跑在 NeoForge / Fabric 上,平台当场接纳,回调在调用里就跑完了,所以这里直接交回身体;没有当场回来(平台在异步接纳)
 * 或被拒,用例就失败并说清楚,不会悄悄拿到 {@code null}。异步的平台(Paper)上跑 GameTest 要等回调,由那一层的适配负责。
 */
public final class Bodies {

    private Bodies() {}

    /** 登记里没有这一条也照样进(不经名册的测试用身体):答复回来时总是"仍然要她"。 */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String name, UUID ownerUuid,
                                    ServerLevel level, Vec3 pos) {
        Arrivals.Arrival<NumenPlayer>[] result = box();
        CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos, () -> true, arrival -> result[0] = arrival);
        return arrived(result[0], name);
    }

    /** 召唤(名册里没有就新建,有就叫醒)。 */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name, ServerLevel level, Vec3 pos) {
        Arrivals.Arrival<NumenPlayer>[] result = box();
        Companions.summon(server, ownerUuid, name, level, pos, arrival -> result[0] = arrival);
        return arrived(result[0], name);
    }

    /** 把名册里的她叫回世界;名册不认得她是 {@code null}。 */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        Arrivals.Arrival<NumenPlayer>[] result = box();
        if (!Companions.respawn(server, companionUuid, arrival -> result[0] = arrival)) {
            return null;
        }
        return arrived(result[0], companionUuid.toString());
    }

    /** 玩家召唤的入口;当场拒绝的话在 {@code problem} 里。 */
    public static Companions.Summoned summonFor(ServerPlayer owner, String name, Boolean creative) {
        Companions.Summoned[] result = new Companions.Summoned[1];
        Companions.summonFor(owner, name, creative, summoned -> result[0] = summoned);
        if (result[0] == null) {
            throw new AssertionError("召唤 " + name + " 没有当场回来:GameTest 的平台应当场接纳");
        }
        return result[0];
    }

    @SuppressWarnings("unchecked")
    private static Arrivals.Arrival<NumenPlayer>[] box() {
        return new Arrivals.Arrival[1];
    }

    private static NumenPlayer arrived(Arrivals.Arrival<NumenPlayer> arrival, String name) {
        if (arrival == null) {
            throw new AssertionError(name + " 没有当场进来:GameTest 的平台应当场接纳");
        }
        if (arrival.body() == null) {
            throw new AssertionError(name + " 没有进来:" + arrival.kind()
                    + (arrival.refusal() == null ? "" : "(" + arrival.refusal().getString() + ")"));
        }
        return arrival.body();
    }
}
