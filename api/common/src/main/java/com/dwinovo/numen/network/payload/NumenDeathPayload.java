package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.Wire;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server → Client: an Numen body died for good. The owner's client-side
 * {@link com.dwinovo.numen.client.agent.EntityAgentLoop} must stop — a dead
 * companion has no body to act through, so it must never call the LLM again.
 *
 * <h2>Why a dedicated signal</h2>
 * Without it, a loop whose body just died keeps dispatching tools and gets back
 * misleading {@code entity not found} / {@code not loaded on client} errors,
 * which the LLM misreads as a transient sync glitch and retries until the loop
 * guard trips. This payload makes death explicit: the loop is hard-stopped and
 * disposed, so no further LLM turns happen.
 *
 * <h2>Death is recoverable (not disposed)</h2>
 * The companion respawns at its owner after a delay (see {@link NumenRespawnPayload}), so the loop
 * is SUSPENDED, not disposed: {@code onEntityDied} abandons any in-flight tool calls, records the
 * cut-off turn as a Halt carrying the death cause (so the brain learns WHY it stopped) and latches it
 * idle. {@code cause} is the vanilla death message ("X was slain by a zombie") for that Halt.
 */
public record NumenDeathPayload(UUID entityUuid, String cause)
        implements CustomPacketPayload, Wire.Oversized<NumenDeathPayload> {

    public static final Type<NumenDeathPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_death"));

    public static final StreamCodec<ByteBuf, NumenDeathPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NumenDeathPayload::entityUuid,
                    Wire.TO_CLIENT.text(), NumenDeathPayload::cause,
                    NumenDeathPayload::new);

    /** 死因是原版的死亡消息,里面的名字长短不归这个包定;长到整包装不下时换成一句说明,死没死、是谁照旧。 */
    @Override
    public NumenDeathPayload shrunk(Predicate<NumenDeathPayload> fits, int bytes, int budget) {
        return new NumenDeathPayload(entityUuid, Wire.TO_CLIENT.tooBig("The cause of death", bytes) + ", so it is not shown.");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenDeathPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.death.accept(p);
    }
}
