package com.dwinovo.numen.client.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side registry of {@link EntityAgentLoop} instances — one per Numen
 * the player is talking to, keyed by the stable {@code entity.getUUID()}.
 *
 * <h2>Why UUID, not network id</h2>
 * The vanilla network {@code entity.getId()} (int) is a per-session handle that
 * changes whenever the entity is recreated — including every cross-dimension
 * trip, where a non-player entity is destroyed and rebuilt with a fresh int id
 * but the same UUID. Keying the loop (and its conversation state) by UUID is
 * what lets the agent survive a Nether/End traversal: the rebuilt body resolves
 * back to the same loop via {@link ClientNumenLookup}. The int id is only ever
 * used as an ephemeral handle for the current tick.
 *
 * <h2>Single-layer architecture</h2>
 * Each Numen carries its own conversation; the owner chats with each entity
 * directly. There is no coordinating brain — see {@link EntityAgentLoop}.
 *
 * <h2>Threading</h2>
 * Client main thread only — every entry point (payload handler, chat screen,
 * interact handler) runs on it. No locks.
 */
public final class AgentLoopRegistry {

    private static final Map<UUID, EntityAgentLoop> ENTITY_LOOPS = new HashMap<>();

    private AgentLoopRegistry() {}

    /** Create-on-first-access. The returned loop is bound to {@code entityUuid} for its lifetime. */
    public static EntityAgentLoop getOrCreate(UUID entityUuid) {
        return ENTITY_LOOPS.computeIfAbsent(entityUuid, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(UUID entityUuid) {
        return Optional.ofNullable(ENTITY_LOOPS.get(entityUuid));
    }

    /**
     * UUIDs of companions with interruptible work ({@link EntityAgentLoop#canInterrupt()} —
     * thinking, awaiting tool results, running a background body task, or holding queued input).
     * These are the heartbeat targets: a server-side chunk-ticket lease should be held for each
     * so the body stays loaded through both model think-time and long-running work.
     */
    public static List<UUID> activeEntityUuids() {
        List<UUID> out = new ArrayList<>();
        for (Map.Entry<UUID, EntityAgentLoop> e : ENTITY_LOOPS.entrySet()) {
            if (e.getValue().canInterrupt()) out.add(e.getKey());
        }
        return out;
    }

    /**
     * UUIDs of EVERY loaded loop, regardless of turn state (idle included). A live persona-library
     * edit propagates to companions currently sitting idle, so this — not the mid-turn-only
     * {@link #activeEntityUuids()} — is what {@code onSavePersona} must iterate.
     */
    public static List<UUID> loadedEntityUuids() {
        return new ArrayList<>(ENTITY_LOOPS.keySet());
    }

    /**
     * Drive every loop once per client tick — currently just the in-flight
     * tool backstop timeout. Wired from each loader's client-tick hook. Safe to
     * iterate directly: no path reached from {@code clientTick} adds or removes
     * loops.
     */
    public static void tickAll() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.clientTick();
        }
    }

    /**
     * 断线静默:对所有 loop 执行 abort——代数戳作废在飞的 LLM 回应、给未决
     * 工具调用合成取消结果、清空半截打字。对话内存保留(同一存档重进接着聊);
     * 跨存档的旧 loop 静置无害(新存档同伴 UUID 不同,寻址不到它们)。
     * 不这么做的话:上一个存档的在飞回合会在下一个存档里落地,工具回合还会
     * 继续链式开新请求——对着不存在的同伴空转烧 token。
     */
    public static void quiesceAll() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.abort();
        }
    }

    /** Drop one entity's loop (e.g. when it dies / unloads). */
    public static void dispose(UUID entityUuid) {
        ENTITY_LOOPS.remove(entityUuid);
    }

    /** Clear everything — called on world-disconnect / explicit reset. */
    public static void clear() {
        ENTITY_LOOPS.clear();
    }
}
