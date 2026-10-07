package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.OwnedBody;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ServerPrograms;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * Client-to-server payload: "stop the program I sent". Two ways to stop, both the client's call because they are about
 * the client's side of things — the owner spoke, the stop button, an external brain took over, the connection is
 * closing:
 * <ul>
 *   <li>{@code cutOff = false}: stop between calls with {@code why} as the reason (the owner spoke, an urgent event the
 *       client synthesised); the call in flight finishes first, a body job it waits for keeps running;</li>
 *   <li>{@code cutOff = true}: the turn is cut off, the program stops at once; {@code stopBody} says whether the body
 *       job was stopped too ({@link CancelTasksPayload} does that), so the receipt tells the truth about it.</li>
 * </ul>
 * Urgent events the server emits itself it judges itself, with the same rule ({@code EventQueue.isUrgent}); they never
 * come through here. Only the owner's program, and only the one named by {@code programId}, is touched.
 */
public record StopProgramPayload(UUID entityUuid, String programId, boolean cutOff, boolean stopBody, String why)
        implements CustomPacketPayload {

    public static final Type<StopProgramPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "stop_program"));

    public static final StreamCodec<ByteBuf, StopProgramPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, StopProgramPayload::entityUuid,
                    Wire.TO_SERVER.text(), StopProgramPayload::programId,
                    ByteBufCodecs.BOOL, StopProgramPayload::cutOff,
                    ByteBufCodecs.BOOL, StopProgramPayload::stopBody,
                    Wire.TO_SERVER.text(), StopProgramPayload::why,
                    StopProgramPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Handler invoked on the server main thread. */
    public static void handle(StopProgramPayload p, ServerPlayer player) {
        NumenPlayer companion = NumenPlayer.findByUuid(player.level().getServer(), p.entityUuid());
        if (companion == null || !companion.isOwnedByPlayer(player.getUUID())) {
            Constants.LOG.debug("[numen-net] stop_program for {} ignored: not the sender's companion", p.entityUuid());
            return;
        }
        if (p.cutOff()) {
            ServerPrograms.cutOff(p.entityUuid(), p.programId(), p.stopBody());
        } else {
            ServerPrograms.interrupt(p.entityUuid(), p.programId(), p.why());
        }
    }
}
