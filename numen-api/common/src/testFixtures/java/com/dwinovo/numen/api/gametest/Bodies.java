package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.entity.Arrivals;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.MojangSkins;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * GameTest 里让她进世界的几个写法:走的是生产里同一条带回调的路({@link CompanionFactory#spawn} 等),只是把结果收下来。
 * 模组上平台当场接纳,回调在调用里就跑完了,直接交回身体。平台在异步接纳的(Paper 的登录事件在异步线程上发)回调晚一点才来,
 * 由 {@link GameTestWorld#await} 在主线程上推着等它来,用例写法与模组上一样,拿到手就是身体。
 * 等到时限还没来、或被拒,用例就失败并说清楚,不会悄悄拿到 {@code null}。
 */
public final class Bodies {

    /** 异步接纳最多等多久。 */
    private static final long WAIT_NANOS = 10_000_000_000L;

    private Bodies() {}

    /** 登记里没有这一条也照样进(不经名册的测试用身体):答复回来时总是"仍然要她"。 */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String label, UUID ownerUuid,
                                    ServerLevel level, Vec3 pos) {
        String name = compliant(label);
        Arrivals.Arrival<NumenPlayer> arrival = await(server,
                then -> CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos, () -> true, then));
        return arrived(arrival, name);
    }

    /**
     * 用例给身体起的名字是写给人看的标签(如 {@code gametest_cartographer}),常常超过产品的名字规则(3 到 16 位字母、数字、下划线,
     * {@link MojangSkins#validName})。真身体的名字走的是真玩家的登录规矩,Paper 上超长的名字连登录事件都造不出来,
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
        String name = compliant(label);
        Arrivals.Arrival<NumenPlayer> arrival = await(server,
                then -> Companions.summon(server, ownerUuid, name, level, pos, then));
        return arrived(arrival, name);
    }

    /** 把名册里的她叫回世界;名册不认得她是 {@code null}。 */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        Object[] result = new Object[1];
        if (!Companions.respawn(server, companionUuid, arrival -> result[0] = arrival)) {
            return null;
        }
        return arrived(Bodies.<Arrivals.Arrival<NumenPlayer>>awaited(server, result), companionUuid.toString());
    }

    /** 玩家召唤的入口;拒绝的话在 {@code problem} 里。 */
    public static Companions.Summoned summonFor(ServerPlayer owner, String name, Boolean creative) {
        Companions.Summoned summoned = await(owner.getServer(), then -> Companions.summonFor(owner, name, creative, then));
        if (summoned == null) {
            throw new AssertionError("召唤 " + name + " 没有在时限内回来:平台的接纳没有答复");
        }
        return summoned;
    }

    /**
     * 经玩家的入口(召唤请求的载荷等,没有回调可等)召唤的她:等到这个名字的玩家进了玩家列表。时限内没进来是 {@code null}。
     */
    public static ServerPlayer awaitPlayer(MinecraftServer server, String name) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        GameTestWorld.INSTANCE.await(server,
                () -> server.getPlayerList().getPlayerByName(name) != null || System.nanoTime() > deadline);
        return server.getPlayerList().getPlayerByName(name);
    }

    /** 发起一趟,等它的回调(模组上已经跑完了,不等)。 */
    private static <T> T await(MinecraftServer server, Consumer<Consumer<T>> start) {
        Object[] result = new Object[1];
        start.accept(value -> result[0] = value);
        return awaited(server, result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T awaited(MinecraftServer server, Object[] result) {
        long deadline = System.nanoTime() + WAIT_NANOS;
        GameTestWorld.INSTANCE.await(server, () -> result[0] != null || System.nanoTime() > deadline);
        return (T) result[0];
    }

    private static NumenPlayer arrived(Arrivals.Arrival<NumenPlayer> arrival, String name) {
        if (arrival == null) {
            throw new AssertionError(name + " 没有在时限内进来:平台的接纳没有答复");
        }
        if (arrival.body() == null) {
            throw new AssertionError(name + " 没有进来:" + arrival.kind()
                    + (arrival.refusal() == null ? "" : "(" + arrival.refusal().getString() + ")"));
        }
        return arrival.body();
    }
}
