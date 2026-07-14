package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * numen-core's server-tick driver of companion tasks. The engine ({@code numen-api})
 * owns the body and is a pure scheduler; <em>task execution</em> is core's, so the
 * per-companion scheduler ({@link CompanionBrain}) lives here (keyed by companion
 * UUID), not on the body.
 *
 * <p>Each tick, for every live {@link NumenPlayer}, the brain ticks the
 * highest-priority active chain and ships finished LLM results back to the owner
 * as {@code TaskResultPayload}. Registered from core's end-of-tick hooks;
 * finalised on body removal / death / owner-abort via the engine's
 * {@code CompanionLifecycle} seam.
 *
 * <p>This class is a thin, signature-preserving facade over {@link CompanionBrain}
 * — {@link #queueFor} and the three lifecycle finalizers are the API that tools
 * ({@code ServerNumenTool.enqueue}, {@code WaitTool}) and the lifecycle wiring
 * depend on, so they are unchanged; the multi-chain scheduling all lives in the
 * brain.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionTickDispatcher {

    private static final Map<UUID, CompanionBrain> BRAINS = new HashMap<>();

    private CompanionTickDispatcher() {}

    private static CompanionBrain brainFor(UUID companionUuid) {
        return BRAINS.computeIfAbsent(companionUuid, k -> new CompanionBrain());
    }

    /** The companion's task queue (created on first use). Body-bound tools enqueue here. */
    public static TaskQueue queueFor(UUID companionUuid) {
        return brainFor(companionUuid).queue;
    }

    public static void tick(MinecraftServer server) {
        Companions.tickRespawns(server);   // timed death recoveries
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer ap) {
                brainFor(ap.getUUID()).tick(ap);
            }
        }
    }

    /**
     * Drop a companion's running task WITHOUT shipping a result — used on death, where the client's
     * {@code NumenDeathPayload} already resolves the in-flight tool call with the death cause (so a
     * second result here would be a duplicate the client ignores).
     */
    public static void clearActiveTask(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late death
        if (brain != null) brain.llm.dropActiveNoResult();     // event must not leak a brain
    }

    /** Owner pressed Stop: cancel the pending queue and the running task (finalized next tick). */
    public static void cancelFor(NumenPlayer player) {
        CompanionBrain brain = BRAINS.get(player.getUUID());   // never create: a late cancel
        if (brain == null) return;                             // packet must not leak a brain
        brain.queue.cancelAll("interrupted by owner");
        brain.llm.cancelActive();
    }

    /**
     * Finalize a companion's running task because the BODY is leaving the world
     * (dormancy / dismissal / death) — the tick loop only visits players still in
     * the player list, so without this the running task is orphaned and its
     * {@code buildResult} side-effects never run (e.g. a mining dig's crack overlay
     * would stay painted on every viewer until chunk reload).
     */
    public static void onCompanionRemoved(NumenPlayer player) {
        UUID id = player.getUUID();
        CompanionBrain brain = BRAINS.get(id);
        if (brain != null) {
            brain.llm.finalizeActive();
            brain.llm.drainResults(player);
        }
        BRAINS.remove(id);   // the body is gone; don't leak its brain
    }
}
