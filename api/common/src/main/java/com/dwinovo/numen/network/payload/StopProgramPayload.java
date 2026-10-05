package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.OwnedBody;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.program.ServerPrograms;
import net.minecraft.network.FriendlyByteBuf;
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

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "stop_program");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(entityUuid);
        Wire.writeText(buf, programId);
        buf.writeBoolean(cutOff);
        buf.writeBoolean(stopBody);
        Wire.writeText(buf, why);
    }

    public static StopProgramPayload read(FriendlyByteBuf buf) {
        return new StopProgramPayload(buf.readUUID(),
                Wire.readText(buf),
                buf.readBoolean(),
                buf.readBoolean(),
                Wire.readText(buf));
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
