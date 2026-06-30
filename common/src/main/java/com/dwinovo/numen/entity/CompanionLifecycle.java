package com.dwinovo.numen.entity;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The seam between the engine (which owns the companion body) and tool packs
 * (which own how tasks execute). The engine fires these as a body changes state;
 * a pack subscribes to finalize its own per-companion work (its server-side task
 * queue, in-flight calls, …). This keeps the engine free of any task-system or
 * transport knowledge — it is a scheduler and a body, nothing more.
 *
 * <p>Listeners are invoked on whatever side fires the event: {@link #fireDeath}
 * and {@link #fireRemove} come from the server (body lifecycle); {@link #fireAbort}
 * comes from the client agent loop (owner interrupt) — a pack's abort listener is
 * the natural place to send its own "stop the body" packet.
 */
public final class CompanionLifecycle {

    private static final List<Consumer<NumenPlayer>> ON_REMOVE = new CopyOnWriteArrayList<>();
    private static final List<Consumer<NumenPlayer>> ON_DEATH = new CopyOnWriteArrayList<>();
    private static final List<Consumer<UUID>> ON_ABORT = new CopyOnWriteArrayList<>();

    private CompanionLifecycle() {}

    /** Body is leaving the world (dormancy / dismissal / death-despawn). */
    public static void onRemove(Consumer<NumenPlayer> listener) { ON_REMOVE.add(listener); }

    /** Body just died, before the corpse is removed. */
    public static void onDeath(Consumer<NumenPlayer> listener) { ON_DEATH.add(listener); }

    /** Owner interrupted this companion's brain — abandon its outstanding work. */
    public static void onAbort(Consumer<UUID> listener) { ON_ABORT.add(listener); }

    public static void fireRemove(NumenPlayer body) { for (var l : ON_REMOVE) l.accept(body); }
    public static void fireDeath(NumenPlayer body) { for (var l : ON_DEATH) l.accept(body); }
    public static void fireAbort(UUID companionUuid) { for (var l : ON_ABORT) l.accept(companionUuid); }
}
