package com.dwinovo.numen.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

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
        NumenPlayer player = new NumenPlayer(server, level, profile, ClientInformation.createDefault());
        FakeConnection connection = new FakeConnection();
        // Restore saved state (position, inventory, health, owner) before joining so the body is at its
        // real location as early as possible. (This explicit restore is kept because placeNewPlayer's own
        // player-data load has not reliably restored a hand-built fake player's .dat in this setup; it is
        // idempotent if placeNewPlayer does load.)
        var savedTag = loadPlayerData(server, player);
        // First spawn has no .dat to restore the owner from; set it explicitly.
        if (player.getOwnerUuid() == null) {
            player.setOwnerUuid(ownerUuid);
        }
        // 1.20.2 起有配置阶段,placeNewPlayer 多带一个 CommonListenerCookie;本代的
        // createInitial 还是单参(1.20.5 才加 transferred 布尔)。
        server.getPlayerList().placeNewPlayer(connection, player,
                CommonListenerCookie.createInitial(profile));
        // An explicit pos (fresh summon, or respawn-at-owner) must WIN over whatever the .dat restored, so
        // apply it AFTER the join: placeNewPlayer internally re-applies the saved .dat, which would otherwise
        // clobber the spawn pos and send a died-then-revived companion back to its death location instead of
        // to its owner. Same-level setPos via moveTo — NOT the teleportTo(ServerLevel,…) dimension-travel
        // overload, which fires EntityTravelToDimensionEvent (tripping some world-protection mods) even for a
        // same-level move. A respawn from dormancy passes null and keeps exactly what the .dat restored.
        if (pos != null) {
            player.moveTo(pos.x, pos.y, pos.z, player.getYRot(), player.getXRot());
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
        return player;
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
        // 1.20.2: PlayerList.load(player) returns a nullable CompoundTag (predates both the
        // Optional wrapper and the ValueInput IO refactor). It already applies the tag to the
        // player internally and returns it; re-applying via Entity.load(CompoundTag) is a no-op-safe
        // belt-and-braces restore of position/inventory for a hand-built fake player.
        net.minecraft.nbt.CompoundTag tag = server.getPlayerList().load(player);
        if (tag != null) {
            player.load(tag);
        }
        return tag;
    }

    /** Save the companion's data and remove it from the world (dormancy). */
    public static void despawn(MinecraftServer server, NumenPlayer player) {
        // Tell tool packs the body is leaving so they can finalize their own
        // per-companion work (e.g. clear a mining crack overlay) instead of leaving
        // it orphaned once the body drops out of the tick loop's player list.
        CompanionLifecycle.fireRemove(player);
        server.getPlayerList().remove(player);
    }
}
