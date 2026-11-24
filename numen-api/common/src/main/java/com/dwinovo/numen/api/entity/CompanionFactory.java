package com.dwinovo.numen.api.entity;

import com.dwinovo.numen.api.CompanionEvent;
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

    /**
     * Bring a companion into the world. On first creation pass a {@code pos}
     * (the spawn location, e.g. beside the owner); on a respawn from dormancy
     * pass {@code null} to keep the position restored from its {@code .dat}.
     *
     * 加入、读档、定位都在这个调用里做完,返回她的身体。
     */
    public static NumenPlayer spawn(MinecraftServer server, UUID companionUuid, String name,
                                    UUID ownerUuid, ServerLevel level, Vec3 pos) {
        GameProfile profile = new GameProfile(companionUuid, name);
        // 借来的正版皮肤(Mojang 签名的 textures,注册表持久化)注入档案——客户端只认
        // 签过名的皮肤数据;没有则回落原版默认皮肤(按 UUID 哈希抽取)。
        CompanionRegistry.Entry reg = CompanionRegistry.get(server).find(companionUuid);
        if (reg != null && !reg.skinValue().isEmpty()) {
            profile.getProperties().put("textures", new com.mojang.authlib.properties.Property(
                    "textures", reg.skinValue(), reg.skinSig().isEmpty() ? null : reg.skinSig()));
        }
        NumenPlayer body = new NumenPlayer(server, level, profile, ClientInformation.createDefault());
        join(server, body, new FakeConnection(), profile, ownerUuid, pos);
        body.fakeClient().arrived();
        return body;
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
        // 存盘时她正死着(死亡到重生之间关了服):和真玩家带着零血上线一样,进来就是死的,还是由原版重生把她放回世界
        if (player.isDeadOrDying()) {
            Companions.onDeath(player);
        }
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
     * Save the companion's data and remove it from the world, the way a real player quits.
     * 这具身体已经不在玩家列表里(被外面请出去了、或换了一具新的身体)就没有什么可移的:再走一遍移除,
     * 再走一遍移除会因为"已经退役"而抛异常。
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
            // 真玩家退出时连接层回调的那一步:公屏"退出了游戏"、存档、移出玩家表,都由原版自己做。她的假连接不会回调,这里替它调
            player.connection.onDisconnect(new net.minecraft.network.DisconnectionDetails(
                    net.minecraft.network.chat.Component.translatable("multiplayer.disconnect.quitting")));
        } finally {
            LEAVING.remove(player);
        }
    }
}
