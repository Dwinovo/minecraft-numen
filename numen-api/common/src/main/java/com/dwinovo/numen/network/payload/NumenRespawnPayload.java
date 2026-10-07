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
 * Server → Client: a companion has respawned at the owner's side after dying — both the same-session
 * timed recovery and the at-login recovery for a companion that died while the owner was away. Carries
 * the {@code cause} so the brain always learns WHY it died, even when a logout wiped the client's
 * in-memory death state. The owner's {@link com.dwinovo.numen.client.agent.EntityAgentLoop} is created
 * if needed and reawakened with a death {@code <event>}.
 */
public record NumenRespawnPayload(UUID entityUuid, String cause)
        implements CustomPacketPayload, Wire.Oversized<NumenRespawnPayload> {

    public static final Type<NumenRespawnPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_respawn"));

    public static final StreamCodec<ByteBuf, NumenRespawnPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NumenRespawnPayload::entityUuid,
                    Wire.TO_CLIENT.text(), NumenRespawnPayload::cause,
                    NumenRespawnPayload::new);

    /** 死因是原版的死亡消息,里面的名字长短不归这个包定;长到整包装不下时换成一句说明,死没死、是谁照旧。 */
    @Override
    public NumenRespawnPayload shrunk(Predicate<NumenRespawnPayload> fits, int bytes, int budget) {
        return new NumenRespawnPayload(entityUuid, Wire.TO_CLIENT.tooBig("The cause of death", bytes) + ", so it is not shown.");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that).
     *  getOrCreate (not get): after a logout the loop may not exist yet — make it so the death event lands. */
    public static void handle(NumenRespawnPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.respawn.accept(p);
    }
}
