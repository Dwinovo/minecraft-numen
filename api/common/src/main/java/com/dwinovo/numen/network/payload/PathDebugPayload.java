package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → Client: one companion's live pathing state for debug overlay
 * rendering. Sent periodically (a few times a second) to owners who toggled
 * debug mode; the client draws it as world-space lines/boxes every frame, so
 * no particle spam is involved. Positions travel as {@code BlockPos#asLong}.
 *
 * <p>Categories: the current path (walked segment onward), the planned next
 * segment, the in-flight search's best partial path, blocks the route will
 * break / place / squeeze through, goal marker boxes, and goal columns
 * (an x/z-only goal rendered as a vertical line; y in the packed long is 0).
 */
public record PathDebugPayload(UUID companionId,
                               List<Long> currentPath, List<Long> nextPath, List<Long> bestPath,
                               List<Long> toBreak, List<Long> toPlace, List<Long> toWalkInto,
                               List<Long> goalBoxes, List<Long> goalColumns)
        implements NumenPayload {

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "path_debug");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        PathDebugPayload p = this;
        buf.writeUUID(p.companionId);
        writeLongs(buf, p.currentPath);
        writeLongs(buf, p.nextPath);
        writeLongs(buf, p.bestPath);
        writeLongs(buf, p.toBreak);
        writeLongs(buf, p.toPlace);
        writeLongs(buf, p.toWalkInto);
        writeLongs(buf, p.goalBoxes);
        writeLongs(buf, p.goalColumns);
    }

    public static PathDebugPayload read(FriendlyByteBuf buf) {
        return new PathDebugPayload(buf.readUUID(),
                readLongs(buf), readLongs(buf), readLongs(buf),
                readLongs(buf), readLongs(buf), readLongs(buf),
                readLongs(buf), readLongs(buf));
    }

    private static void writeLongs(FriendlyByteBuf buf, List<Long> list) {
        buf.writeVarInt(list.size());
        for (long v : list) {
            buf.writeLong(v);
        }
    }

    private static List<Long> readLongs(FriendlyByteBuf buf) {
        int n = buf.readVarInt();
        List<Long> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readLong());
        }
        return list;
    }

    /** Client-side handler (client main thread): stash for the frame renderer. */
    public static void handle(PathDebugPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.pathDebug.accept(p);
    }
}
