package com.dwinovo.numen.entity;

import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.platform.Services;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates companion lifecycle on top of {@link CompanionFactory} (body
 * spawn/despawn) and {@link CompanionRegistry} (the persistent index). The body
 * persists as a player {@code .dat}; the registry remembers it exists so it can
 * be recreated when its owner returns or a tool call arrives.
 */
@com.dwinovo.numen.api.Internal
public final class Companions {

    /** 死后躺多久再在主人身边复活。5 秒:够让主人看清"她死了"这件事,
     *  又短到不会把她整场战斗排除在外。 */
    private static final long RESPAWN_DELAY_TICKS = 5 * 20;

    private Companions() {}

    /**
     * Summon the owner's companion called {@code name}. IDEMPOTENT per (owner, name): if one already
     * exists it is reused — already live → returned as-is; dormant → brought back. Only a name with no
     * existing companion mints a fresh one. (The old "fresh random UUID every summon" minted same-name
     * duplicates that all respawned on login — that's the duplicate-companion bug.)
     */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos) {
        return summon(server, ownerUuid, name, level, pos, null);
    }

    /** As {@link #summon(MinecraftServer, UUID, String, ServerLevel, Vec3)} with an optional
     *  borrowed skin (Mojang 签名的 textures,见 {@link MojangSkins})。重复召唤携带皮肤 =
     *  换肤:注册表更新后,休眠体这次重建就生效,活体等下次重建。 */
    public static NumenPlayer summon(MinecraftServer server, UUID ownerUuid, String name,
                                      ServerLevel level, Vec3 pos, MojangSkins.Skin skin) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        UUID existing = findByOwnerName(server, ownerUuid, name);
        if (existing != null) {
            if (skin != null) {
                CompanionRegistry.Entry e = reg.find(existing);
                if (e != null) reg.put(existing, e.withSkin(skin.value(), skin.signature()));
                // 换肤必须重建身体才看得见(GameProfile 只在构造时注入):活体先落盘
                // 休眠,紧接着的 respawn 立即以带新皮肤的档案重建,位置物品全保留。
                NumenPlayer live = NumenPlayer.findByUuid(server, existing);
                if (live != null) {
                    CompanionFactory.despawn(server, live);
                }
            }
            NumenPlayer body = respawn(server, existing);
            if (body != null) return body;
            reg.remove(existing);   // stale entry (no .dat) — replace it
        }
        UUID companionUuid = UUID.randomUUID();
        Vec3 safe = SafeSpawn.findNear(level, pos);
        if (safe != null) pos = safe;   // no safe spot around → keep the summoner's own position
        // 先入册再造体:CompanionFactory.spawn 从注册表读皮肤,所有出生路径共用一个注入点。
        CompanionRegistry.Entry fresh = new CompanionRegistry.Entry(
                name, ownerUuid, level.dimension(), net.minecraft.core.BlockPos.containing(pos));
        if (skin != null) fresh = fresh.withSkin(skin.value(), skin.signature());
        reg.put(companionUuid, fresh);
        NumenPlayer body = CompanionFactory.spawn(server, companionUuid, name, ownerUuid, level, pos);
        reg.put(companionUuid, fresh.movedTo(level.dimension(), body.blockPosition()));
        return body;
    }

    /** 主人名下叫这个名字的同伴,没有则 null。判断规则在 {@link CompanionRoster#findByName}
     *  (纯 JVM,可单测);这里只负责把注册表摊平成它认得的形状。 */
    private static UUID findByOwnerName(MinecraftServer server, UUID ownerUuid, String name) {
        return CompanionRoster.findByName(rowsOf(server, ownerUuid), name);
    }

    /** 注册表 → 决策层认得的纯数据行。 */
    private static List<CompanionRoster.Row> rowsOf(MinecraftServer server, UUID ownerUuid) {
        List<CompanionRoster.Row> rows = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            rows.add(new CompanionRoster.Row(e.getKey(), e.getValue().name(), e.getValue().diedAt()));
        }
        return rows;
    }

    /**
     * Bring a dormant companion back from its catalog entry + {@code .dat}
     * (position/inventory restored from disk). Returns the already-live body if
     * it is spawned, or {@code null} if it is unknown to the registry.
     */
    public static NumenPlayer respawn(MinecraftServer server, UUID companionUuid) {
        NumenPlayer live = NumenPlayer.findByUuid(server, companionUuid);
        if (live != null) return live;
        CompanionRegistry.Entry entry = CompanionRegistry.get(server).find(companionUuid);
        if (entry == null) return null;
        ServerLevel level = server.getLevel(entry.dimension());
        if (level == null) level = server.overworld();
        // pos=null: keep the position restored from the .dat.
        return CompanionFactory.spawn(server, companionUuid, entry.name(), entry.owner(), level, null);
    }

    /** When an owner logs in, bring back every companion of theirs. A companion that DIED while the owner
     *  was away (death state persisted in the registry — survives the logout) is respawned-at-owner now
     *  AND told why it died; a live one is just restored from its {@code .dat}. */
    public static void respawnAllOwnedBy(MinecraftServer server, UUID ownerUuid) {
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            if (e.getValue().diedAt() > 0L) {
                if (owner != null) respawnDead(server, e.getKey(), e.getValue(), owner);
            } else {
                respawn(server, e.getKey());
            }
        }
        if (owner != null) {
            replayOutbox(server, ownerUuid, owner);
        }
    }

    /**
     * 主人登录:把他离线期间攒下的世界事件补发给客户端。
     *
     * <p>他不在的时候她照样在干活——任务跑完了、被怪打了、跨了维度。从前这些
     * 一律直接丢,于是"我帮你把矿挖完了"这件最值得说的事恰好最容易丢。
     * 攒满被丢掉的条数也如实说一句:丢弃可以,无声消失不行。
     */
    private static void replayOutbox(MinecraftServer server, UUID ownerUuid, ServerPlayer owner) {
        EventOutbox outbox = EventOutbox.get(server);
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).ownedBy(ownerUuid)) {
            EventOutbox.Box box = outbox.take(e.getKey());
            if (box.pending().isEmpty() && box.dropped() <= 0) {
                continue;
            }
            for (EventOutbox.Pending p : box.pending()) {
                Services.NETWORK.sendToPlayer(owner,
                        new com.dwinovo.numen.network.payload.NumenEventPayload(e.getKey(), p.xml(), p.urgent()));
            }
            com.dwinovo.numen.Constants.LOG.info(
                    "[numen-outbox] {} 补发 {} 条离线事件(另有 {} 条因攒满被丢弃)",
                    e.getKey(), box.pending().size(), box.dropped());
            if (box.dropped() > 0) {
                Services.NETWORK.sendToPlayer(owner,
                        new com.dwinovo.numen.network.payload.NumenEventPayload(e.getKey(),
                                "<event kind=\"body_log\">你不在的时候还发生了大约 " + box.dropped()
                                        + " 件事,没能记下来</event>", false));
            }
        }
    }

    /**
     * A companion just DIED (detected in {@link NumenPlayer#tick}). The death itself is left fully
     * vanilla — drops / a grave mod / keepInventory all run because it's a real ServerPlayer death.
     * We only: stop the brain (the owner's loop suspends on {@link NumenDeathPayload}, resolving the
     * in-flight tool call with the death cause), heal the body so its saved {@code .dat} is whole, and
     * queue a timed respawn at the owner. The corpse is removed AFTER this tick (a fake player isn't
     * auto-removed on death — it would sit at 0 HP forever waiting for a respawn packet that never comes).
     */
    public static void onDeath(NumenPlayer body) {
        MinecraftServer server = body.level().getServer();
        if (server == null) return;
        UUID uuid = body.getUUID();
        String cause = body.getCombatTracker().getDeathMessage().getString();
        if (cause == null || cause.isBlank()) cause = "未知原因";
        CompanionLifecycle.fireDeath(body);   // no result shipped — the death payload drives the client
        ServerPlayer owner = body.resolveOwnerPlayer();
        if (owner != null) {   // immediate, same-session
            Services.NETWORK.sendToPlayer(owner, new NumenDeathPayload(uuid, cause));
        }
        // Persist the death (cause + game-time) in the world-saved registry so it survives a logout during
        // the respawn window — without this, a relog lost the pending state and the body silently respawned
        // "alive" with an empty inventory and no idea it had died.
        CompanionRegistry.get(server).markDead(uuid, cause, server.overworld().getGameTime());
        // 死亡状态进了注册表,名册才说得出"她还在,只是躺着"——面板的倒计时读这个。
        // 少了这一推,主人在死亡窗口里重登就会看见她凭空消失。
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
        body.setHealth(body.getMaxHealth());             // saved .dat is a healthy body for the respawn
        server.execute(() -> CompanionFactory.despawn(server, body));   // remove the corpse safely after the tick
    }

    /**
     * Bring back any companion whose post-death timer has elapsed, AT ITS OWNER (covers dimension
     * follow too — it returns in whatever dimension the owner is now in). Owner offline → keep waiting
     * (their client-side brain can't run anyway); it respawns the moment they're back. Called each tick.
     */
    public static void tickRespawns(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : CompanionRegistry.get(server).pendingDead()) {
            CompanionRegistry.Entry entry = e.getValue();
            if (now - entry.diedAt() < RESPAWN_DELAY_TICKS) continue;
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.owner());
            if (owner == null) continue;                            // owner offline — wait for login
            // No safe spot right now → quietly retry next tick until the owner reaches open
            // space (the maid/vanilla-pet convention: never nag about a transient squeeze).
            respawnDead(server, e.getKey(), entry, owner);
        }
    }

    /** Respawn a dead companion at its owner, clear the death state, and tell the brain it died + why
     *  (the cause rides the respawn payload, so it works even after a logout cleared the client's memory).
     *  Returns false when no safe landing spot exists near the owner right now (tight tunnel, crawling,
     *  deep water) — the death state stays pending and the ticker retries until the owner reaches open
     *  space. Spawning anyway wedged the body into blocks: suffocate → die → respawn into the same spot,
     *  a death loop until the owner happened to move. */
    private static boolean respawnDead(MinecraftServer server, UUID uuid, CompanionRegistry.Entry entry,
                                       ServerPlayer owner) {
        ServerLevel level = (ServerLevel) owner.level();
        Vec3 pos = SafeSpawn.findNear(level, owner.position());
        if (pos == null && owner.onGround() && SafeSpawn.hasStandingRoom(level, owner.position())) {
            pos = owner.position();   // non-full-block floor (slab/carpet): the owner's own spot fits
        }
        if (pos == null) return false;
        NumenPlayer body = CompanionFactory.spawn(server, uuid, entry.name(), entry.owner(), level, pos);
        body.setHealth(body.getMaxHealth());
        body.clearFire();
        CompanionRegistry.get(server).markAlive(uuid);
        syncRosterToOwner(server, owner);
        Services.NETWORK.sendToPlayer(owner, new NumenRespawnPayload(uuid, entry.deathCause()));
        return true;
    }

    /**
     * 把主人名下<b>存在的</b>同伴推给他的客户端。
     *
     * <p>取自持久的 {@link CompanionRegistry},不是玩家列表——死了、正等复活、休眠的
     * 同伴都还存在,只是此刻不在世界里。客户端拿这份名册对账并<b>删除已遣散同伴的
     * 本地数据</b>,所以名册的语义必须是"存在";用"此刻活着"做过一版,结果是同伴一死
     * 就被当成遣散,数据整个删掉。判断规则在 {@link CompanionRoster}(纯 JVM,可单测)。
     *
     * <p>任何"存在或存活状态"的变化之后都要调:登录、召唤、遣散、死亡、复活。
     */
    public static void syncRosterToOwner(MinecraftServer server, ServerPlayer owner) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<CompanionRoster.Row> rows = rowsOf(server, owner.getUUID());
        List<CompanionListPayload.Entry> list = new ArrayList<>();
        for (CompanionRoster.Line l : CompanionRoster.build(
                rows, server.overworld().getGameTime(), RESPAWN_DELAY_TICKS)) {
            list.add(new CompanionListPayload.Entry(l.uuid(), l.name(), l.respawnInMs()));
        }
        Services.NETWORK.sendToPlayer(owner, new CompanionListPayload(reg.worldId(), list));
    }

    /**
     * The companion crossed into a new dimension — on its OWN (it travels where it likes, not tied to
     * the owner). Tell its brain, ambient: it rides along on the next owner-driven turn rather than
     * spending a fresh LLM call just to note the move. Called from each loader's dimension-change hook.
     */
    public static void onDimensionChanged(NumenPlayer body) {
        String dim = body.level().dimension().location().toString();
        com.dwinovo.numen.event.NumenEvents.emit(body,
                com.dwinovo.numen.event.NumenEvents.Kind.DIMENSION_CHANGE,
                java.util.Map.of("to", dim),
                "你进入了 " + dim + "。留意这个维度的环境和危险。", false);
    }

    /** Save the companion to its {@code .dat} and remove it from the world (dormancy). */
    public static void dormant(MinecraftServer server, NumenPlayer body) {
        // Refresh the respawn hint before the body leaves.
        CompanionRegistry reg = CompanionRegistry.get(server);
        CompanionRegistry.Entry prev = reg.find(body.getUUID());
        if (prev != null) {
            reg.put(body.getUUID(),
                    prev.movedTo(((ServerLevel) body.level()).dimension(), body.blockPosition()));
        }
        CompanionFactory.despawn(server, body);
    }

    /** 遣散一只活体:身体离场 + 永久除名。 */
    public static void dismiss(MinecraftServer server, NumenPlayer body) {
        UUID ownerUuid = body.getOwnerUuid();
        UUID uuid = body.getUUID();
        CompanionFactory.despawn(server, body);
        forget(server, ownerUuid, List.of(uuid));
    }

    /**
     * <b>永久除名的唯一出口</b>。删注册表条目 = 这只同伴不再存在,再把新名册推给主人
     * ——客户端据此把她的家目录一并删掉。
     *
     * <p>遣散的三条路(面板 ✕、{@code /numen despawn}、休眠体)都从这儿走:一个动作
     * 一个出口,才不会出现"删了条目却忘了通知"或者"通知了却没删干净"的半截状态。
     * 主人不在线就只删条目——他下次登录收到的名册照样是对的,对账是<b>状态同步</b>
     * 不是事件通知,漏不掉。
     */
    public static void forget(MinecraftServer server, UUID ownerUuid, Collection<UUID> uuids) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        EventOutbox outbox = EventOutbox.get(server);
        for (UUID uuid : uuids) {
            reg.remove(uuid);
            outbox.forget(uuid);   // 她攒的事件跟着走:没人会再收
        }
        ServerPlayer owner = ownerUuid == null ? null : server.getPlayerList().getPlayer(ownerUuid);
        if (owner != null) {
            syncRosterToOwner(server, owner);
        }
    }

    /**
     * Permanently dismiss EVERY companion of {@code ownerUuid} named {@code name} — gone for good, it
     * will NOT come back on login. Removes both live bodies and registry entries, so it also cleans up
     * any same-name duplicates that the old non-idempotent summon left behind. Returns how many it
     * dismissed. (The {@code .dat} files orphan harmlessly — with no registry entry nothing respawns
     * them.)
     */
    public static int dismissByName(MinecraftServer server, UUID ownerUuid, String name) {
        CompanionRegistry reg = CompanionRegistry.get(server);
        List<UUID> ids = new ArrayList<>();
        for (Map.Entry<UUID, CompanionRegistry.Entry> e : reg.ownedBy(ownerUuid)) {
            if (e.getValue().name().equals(name)) ids.add(e.getKey());
        }
        // Defensive: also catch a live body of that name somehow missing from the registry.
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer a && a.isOwnedByPlayer(ownerUuid)
                    && a.getName().getString().equals(name) && !ids.contains(a.getUUID())) {
                ids.add(a.getUUID());
            }
        }
        for (UUID id : ids) {
            NumenPlayer live = NumenPlayer.findByUuid(server, id);
            if (live != null) CompanionFactory.despawn(server, live);
        }
        forget(server, ownerUuid, ids);   // 一次除名、一次推送
        return ids.size();
    }
}
