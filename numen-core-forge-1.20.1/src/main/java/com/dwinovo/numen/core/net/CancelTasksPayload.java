package com.dwinovo.numen.core.net;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

public record CancelTasksPayload(UUID entityUuid) {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "cancel_tasks");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
    }

    public static CancelTasksPayload decode(FriendlyByteBuf buf) {
        return new CancelTasksPayload(buf.readUUID());
    }

    public static void handle(CancelTasksPayload p, ServerPlayer player) {
        NumenPlayer numen = NumenPlayer.findByUuid(player.level.getServer(), p.entityUuid());
        if (numen == null) {
            Constants.LOG.debug("[numen-net] cancel_tasks for unknown entity {}", p.entityUuid());
            return;
        }
        if (!numen.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.warn("[numen-net] cancel_tasks rejected from {}: not the owner",
                    player.getName().getString());
            return;
        }
        com.dwinovo.numen.core.task.CompanionTickDispatcher.cancelFor(numen);
        Constants.LOG.info("[numen-net] cancel_tasks on entity {} for {}",
                p.entityUuid(), player.getName().getString());
    }
}
