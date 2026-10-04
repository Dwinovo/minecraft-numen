package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "the owner pressed Stop — cancel everything my
 * entity is doing". Sent by {@code EntityAgentLoop.abort()} alongside the
 * client-side turn teardown, so the interrupt stops the <em>body</em> (the
 * running task: walking, mining, placing), not just the model conversation.
 *
 * <h2>Trust model</h2>
 * Owner check only — no distance check, deliberately: the owner must be able
 * to stop a companion that has wandered far away. Cancelling is the one action
 * that is always safe to allow from anywhere.
 *
 * <p>A program the owner sent is stopped by {@link StopProgramPayload}; the task's
 * own end comes as a task_finished event (or in the receipt of a program still
 * waiting for it). This payload's job is purely the body stop.
 */
public record CancelTasksPayload(UUID entityUuid) implements CustomPacketPayload {

    public static final Type<CancelTasksPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "cancel_tasks"));

    public static final StreamCodec<ByteBuf, CancelTasksPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, CancelTasksPayload::entityUuid,
                    CancelTasksPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(CancelTasksPayload p, ServerPlayer player) {
        // Cross-dimension lookup: the owner must be able to stop a companion
        // that has wandered into another dimension or out of view distance.
        NumenPlayer numen = NumenPlayer.findByUuid(player.level().getServer(), p.entityUuid());
        if (numen == null) {
            Constants.LOG.debug("[numen-net] cancel_tasks for unknown entity {}", p.entityUuid());
            return;
        }
        if (!numen.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.warn("[numen-net] ✗ cancel_tasks rejected from {}: not the owner",
                    player.getName().getString());
            return;
        }
        com.dwinovo.numen.task.CompanionTickDispatcher.cancelFor(numen);
        Constants.LOG.info("[numen-net] ✓ cancel_tasks on entity {} for {}",
                p.entityUuid(), player.getName().getString());
    }
}
