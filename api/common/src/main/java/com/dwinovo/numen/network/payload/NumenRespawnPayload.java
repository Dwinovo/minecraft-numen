package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → Client: a companion has respawned at the owner's side after dying — both the same-session
 * timed recovery and the at-login recovery for a companion that died while the owner was away. Carries
 * the {@code cause} so the brain always learns WHY it died, even when a logout wiped the client's
 * in-memory death state. The owner's {@link com.dwinovo.numen.client.agent.EntityAgentLoop} is created
 * if needed and reawakened with a death {@code <event>}.
 */
public record NumenRespawnPayload(UUID entityUuid, String cause) implements NumenPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "numen_respawn");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        buf.writeUtf(cause);
    }

    public static NumenRespawnPayload read(FriendlyByteBuf buf) {
        return new NumenRespawnPayload(buf.readUUID(), buf.readUtf());
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that).
     *  getOrCreate (not get): after a logout the loop may not exist yet — make it so the death event lands. */
    public static void handle(NumenRespawnPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.respawn.accept(p);
    }
}
