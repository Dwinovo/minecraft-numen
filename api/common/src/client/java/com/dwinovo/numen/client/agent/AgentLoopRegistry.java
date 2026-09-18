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

    /**
     * Create-on-first-access. The returned loop is bound to {@code entityUuid} for its lifetime.
     *
     * <p><b>正常情况下这里不会真的新建</b>:名册一到就给册上每只同伴都备好了 loop
     * (见 {@code ClientPayloadHandlers.handleCompanionList})——「她有没有大脑」跟
     * 「她存不存在」对齐,不跟「谁先碰过她」对齐。这道 create 只是兜底。
     */
    public static EntityAgentLoop getOrCreate(UUID entityUuid) {
        return ENTITY_LOOPS.computeIfAbsent(entityUuid, EntityAgentLoop::new);
    }

    /** Read-only lookup; never creates. Used by the S→C result handler. */
    public static Optional<EntityAgentLoop> get(UUID entityUuid) {
        return Optional.ofNullable(ENTITY_LOOPS.get(entityUuid));
    }

    /**
     * UUIDs of EVERY loaded loop, regardless of turn state (idle included) — a live persona-library edit
     * propagates to companions currently sitting idle too.
     */
    public static List<UUID> loadedEntityUuids() {
        return new ArrayList<>(ENTITY_LOOPS.keySet());
    }

    /**
     * Drive every loop once per client tick (tool backstop, presentation, the external-driver flip, the
     * kernel's ripeness check). Wired from each loader's client-tick hook. Safe to iterate directly: no
     * path reached from {@code clientTick} adds or removes loops.
     *
     * <p>只在连着世界时驱动:循环属于一次连接,标题画面上身体够不着、工具发不出去。登出的
     * {@code halt(DISCONNECT)} 不置停牌、不清队列,排着的输入等重连后照常处理——不在这里拦的话,
     * 断线前排上的一句话会在标题画面上开起一次 run。
     */
    public static void tickAll() {
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            return;
        }
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.clientTick();
        }
    }

    /**
     * 断线静默:对所有 loop 执行 {@link EntityAgentLoop#quiesce}({@code halt(DISCONNECT)})——取消在飞的
     * 模型调用、放弃未决工具调用并记下切断点。<b>不叫停身体</b>:她还在服务器里,任务照样跑完,
     * 收尾进离线出箱。对话内存保留(同一存档重进接着聊);跨存档的旧 loop 静置无害(新存档同伴
     * UUID 不同,寻址不到它们)。不这么做的话:上一个存档的在飞回合会在下一个存档里落地,工具
     * 回合还会继续链式开新请求——对着不存在的同伴空转烧 token。
     */
    public static void quiesceAll() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.quiesce();
        }
    }

    /**
     * 停掉一只同伴的大脑并摘出表——她不在了(遣散/离场)时调。
     *
     * <p>先 {@code halt(DISPOSE)} 再摘表:光摘表拦不住已经在飞的请求,响应回来照样往磁盘写,
     * 把刚删掉的数据写回来。
     */
    public static void dispose(UUID entityUuid) {
        EntityAgentLoop loop = ENTITY_LOOPS.remove(entityUuid);
        if (loop != null) {
            loop.dispose();
        }
    }

    /**
     * 清表(调试命令 RESET_LOOPS)。每个 loop 先 {@code halt(DISPOSE)}:同伴还在,下一次
     * {@link #getOrCreate} 会从同一份会话文件重建她的大脑,旧 loop 的在飞回合不能再往那份文件里写。
     */
    public static void clear() {
        for (EntityAgentLoop loop : ENTITY_LOOPS.values()) {
            loop.dispose();
        }
        ENTITY_LOOPS.clear();
    }
}
