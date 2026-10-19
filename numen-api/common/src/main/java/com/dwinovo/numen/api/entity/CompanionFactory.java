package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.api.platform.Services;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Spawns and despawns companion {@link NumenPlayer} bodies through the vanilla
 * player-join path — entirely public API, no loader-specific construction needed
 * (the only fake piece is {@link FakeConnection}, which is common).
 *
 * <p>{@link net.minecraft.server.players.PlayerList#placeNewPlayer} adds the body
 * to the player list (→ chunk loading for free) and to the level, but does NOT
 * load a hand-built fake player's {@code .dat} (that path is tied to the real
 * login flow) — so {@link #spawn} restores position / inventory / owner from disk
 * explicitly afterwards.
 * {@link net.minecraft.server.players.PlayerList#remove} saves that data back and
 * removes the body — so despawn is a clean, persisted dormancy.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionFactory {

    private CompanionFactory() {}

    /** 在路上的身体的登记与编排(主线程),见 {@link Arrivals}。 */
    private static final Arrivals<NumenPlayer> ARRIVALS = new Arrivals<>();

    static {
        com.dwinovo.numen.api.platform.ServerLifecycle.onStopped(ARRIVALS::clear);
    }

    /** 这具身体正在被平台接纳、还没进世界。 */
    public static boolean isArriving(UUID companionUuid) {
        return ARRIVALS.isArriving(companionUuid);
    }

    /** 哪具在路上的身体占着这个名字;没有是空。 */
    public static java.util.Optional<UUID> arrivingNamed(String name) {
        return ARRIVALS.arrivingNamed(name);
    }

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     *
     * <p>先问平台接不接纳这具身体({@link com.dwinovo.numen.api.platform.services.IBodyAdmission}):模组上当场答应,
     * {@code then} 在这个调用里就跑完;异步的平台(Paper 要给登录插件发事件)答复在以后的某一刻,{@code then} 在那时的主线程上跑。
     * 受理的那一刻起她就"在路上"(编排见 {@link Arrivals});答复回来、加入之前再问一次 {@code stillWanted}。
     * 接纳之后的加入、读档、定位都在 {@code then} 之前做完。
     *
     * @param stillWanted 平台答复回来时,调用方是不是仍然要她(名册里还有这一条、没被遣散)
     */
    public static void spawn(MinecraftServer server, UUID companionUuid, String name,
                             UUID ownerUuid, ServerLevel level, Vec3 pos, BooleanSupplier stillWanted,
                             Consumer<Arrivals.Arrival<NumenPlayer>> then) {
        GameProfile profile = new GameProfile(companionUuid, name);
        // 借来的正版皮肤(Mojang 签名的 textures,注册表持久化)注入档案——客户端只认
        // 签过名的皮肤数据;没有则回落原版默认皮肤(按 UUID 哈希抽取)。
        CompanionRegistry.Entry reg = CompanionRegistry.get(server).find(companionUuid);
        if (reg != null && !reg.skinValue().isEmpty()) {
            profile.getProperties().put("textures", new com.mojang.authlib.properties.Property(
                    "textures", reg.skinValue(), reg.skinSig().isEmpty() ? null : reg.skinSig()));
        }
        // 身体与连接在受理之后才造:已经在路上的那一具再来,不白造一个
        NumenPlayer[] body = new NumenPlayer[1];
        FakeConnection[] connection = new FakeConnection[1];
        ARRIVALS.arrive(companionUuid, name, verdict -> {
            body[0] = new NumenPlayer(server, level, profile, ClientInformation.createDefault());
            connection[0] = new FakeConnection();
            Services.admission().admit(body[0], connection[0], verdict);
        }, stillWanted, () -> {
            join(server, body[0], connection[0], profile, ownerUuid, pos);
            body[0].fakeClient().arrived();
            return body[0];
        }, then);
    }

    private static void join(MinecraftServer server, NumenPlayer player, FakeConnection connection, GameProfile profile,
                             UUID ownerUuid, Vec3 pos) {
        // Restore saved state (position, inventory, health, owner) before joining so the body is at its
        // real location as early as possible. (This explicit restore is kept because placeNewPlayer's own
        // player-data load has not reliably restored a hand-built fake player's .dat in this setup; it is
        // idempotent if placeNewPlayer does load.)
        var savedTag = loadPlayerData(server, player);
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile, false));
        // 加入之后平台要做的事(Paper:退出玩家区块加载、权限委托给主人),在定位之前做:定位之后她周围的区块就不该再按玩家的方式去加载
        Services.admission().joined(player);
        // An explicit pos (fresh summon, or respawn-at-owner) must WIN over whatever the .dat restored, so
        // apply it AFTER the join: placeNewPlayer internally re-applies the saved .dat, which would otherwise
        // clobber the spawn pos and send a died-then-revived companion back to its death location instead of
        // to its owner. Same-level teleportTo — NOT the teleportTo(ServerLevel,…) dimension-travel
        // overload, which fires EntityTravelToDimensionEvent (tripping some world-protection mods) even for a
        // same-level move. A respawn from dormancy passes null and keeps exactly what the .dat restored.
        //
        // 走 teleportTo 而不是裸 moveTo:玩家的位置基线在网络层(awaitingPosition / lastGood),
        // placeNewPlayer 内部刚按加入点记过一次。裸 moveTo 只挪身体不动基线,下一刻
        // {@link FakeClient} 交还传送回执时,原版会照着旧基线把她拽回加入点。
        if (pos != null) {
            player.teleportTo(pos.x, pos.y, pos.z);
        }
        // 假玩家没有客户端上报的模型定制:点亮全部皮肤覆盖层与披风,否则只显示单层基础皮肤。
        // 每次 spawn(首建与重生)都重设——该字节是同步实体数据、不随 .dat 存取。
        player.showAllSkinLayers();
        // 模式策略:首次召唤一律生存——不继承创造世界的默认档,同伴的整套设计
        // (采集掉落/真实战斗/可恢复死亡)是生存形状的,placeNewPlayer 会把
        // 创造世界的默认档连秒破无掉落一起塞过来。老同伴尊重主人上次设的档
        // (面板芯片 / /gamemode 指令,存在 .dat 的 playerGameType 里),但只认
        // 生存/创造两档,其余一律归生存。placeNewPlayer 之后强制,保证胜出。
        GameType mode = GameType.SURVIVAL;
        if (savedTag != null && savedTag.contains("playerGameType")
                && GameType.byId(savedTag.getInt("playerGameType")) == GameType.CREATIVE) {
            mode = GameType.CREATIVE;
        }
        player.setGameMode(mode);
        // 身体齐了、进了玩家列表,插件此刻可以对它下命令(改外观、发指令都需要它在列表里)
        CompanionEvents.fire(CompanionEvent.SPAWN, player);
    }

    /**
     * Restore a fake player's saved state from its playerdata {@code .dat}
     * ({@link net.minecraft.server.players.PlayerList#loadPlayerData} +
     * {@link net.minecraft.world.entity.Entity#load}). {@code placeNewPlayer}
     * skips this for hand-constructed players, so we invoke the same load
     * ourselves. No-op on first summon (no file yet).
     */
    /** @return 载入的 .dat(供上层读 playerGameType 等玩家级字段);首次召唤无档返回 null */
    private static net.minecraft.nbt.CompoundTag loadPlayerData(MinecraftServer server, NumenPlayer player) {
        // 1.21.5: PlayerList.load(player) returns Optional<CompoundTag> (predates the
        // ValueInput IO refactor); Entity.load(CompoundTag) consumes it directly.
        var maybe = server.getPlayerList().load(player);
        maybe.ifPresent(player::load);
        return maybe.orElse(null);
    }

    /** 正由 Numen 自己移出世界的身体(主线程)。有的平台移除玩家时会发"玩家退出"事件,靠这个分得清是 Numen 收的还是外面请出去的。 */
    private static final Set<NumenPlayer> LEAVING = new HashSet<>();

    /** 这具身体此刻正被 Numen 自己移出世界({@link #despawn}):不是被外面的人或插件请出去的。 */
    public static boolean isLeaving(NumenPlayer player) {
        return LEAVING.contains(player);
    }

    /**
     * Save the companion's data and remove it from the world (dormancy).
     * 这具身体已经不在玩家列表里(被外面请出去了、或换了一具新的身体)就没有什么可移的:再走一遍移除,
     * 有的平台(Paper)会因为"已经退役"而抛异常。
     */
    public static void despawn(MinecraftServer server, NumenPlayer player) {
        if (server.getPlayerList().getPlayer(player.getUUID()) != player) {
            return;
        }
        // Tell tool packs the body is leaving so they can finalize their own
        // per-companion work (e.g. clear a mining crack overlay) instead of leaving
        // it orphaned once the body drops out of the tick loop's player list.
        CompanionEvents.fire(CompanionEvent.REMOVE, player);
        LEAVING.add(player);
        try {
            server.getPlayerList().remove(player);
        } finally {
            LEAVING.remove(player);
        }
    }
}
