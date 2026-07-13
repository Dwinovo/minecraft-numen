package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.AgentLoopRegistry;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server -> Client: an Numen body died for good. The owner's client-side
 * {@link com.dwinovo.numen.client.agent.EntityAgentLoop} must stop -- a dead
 * companion has no body to act through, so it must never call the LLM again.
 *
 * <p>Without it, a loop whose body just died keeps dispatching tools and gets back
 * misleading {@code entity not found} / {@code not loaded on client} errors,
 * which the LLM misreads as a transient sync glitch and retries until the loop
 * guard trips. This payload makes death explicit: the loop is hard-stopped and
 * disposed, so no further LLM turns happen.
 *
 * <p>Death is recoverable (not disposed): the companion respawns at its owner
 * after a delay (see {@link NumenRespawnPayload}), so the loop is SUSPENDED, not
 * disposed: {@code onEntityDied} resolves any in-flight tool calls with the death
 * cause (so the conversation stays valid and the brain learns WHY it stopped) and
 * latches it idle. {@code cause} is the vanilla death message ("X was slain by a
 * zombie") for that tool result.
 */
public record NumenDeathPayload(UUID entityUuid, String cause, long respawnDelayMs) {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "numen_death");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeUtf(cause);
        buf.writeLong(respawnDelayMs);
    }

    public static NumenDeathPayload decode(FriendlyByteBuf buf) {
        return new NumenDeathPayload(buf.readUUID(), buf.readUtf(), buf.readLong());
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenDeathPayload p) {
        Constants.LOG.info("[numen-net] numen_death entity={} ({}) -- suspending loop", p.entityUuid(), p.cause());
        AgentLoopRegistry.get(p.entityUuid()).ifPresent(loop -> loop.onEntityDied(p.cause()));
        // Keep it in the roster (marked dead) so the HUD / rail can show the respawn countdown;
        // it goes live again on NumenRespawnPayload.
        com.dwinovo.numen.client.agent.ClientDeaths.markDead(
                p.entityUuid(), System.currentTimeMillis() + p.respawnDelayMs());
    }
}
