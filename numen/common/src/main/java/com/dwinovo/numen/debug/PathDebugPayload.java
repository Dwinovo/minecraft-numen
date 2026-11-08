package com.dwinovo.numen.debug;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.network.NumenPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

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

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "path_debug");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(companionId);
        writeLongs(buf, currentPath);
        writeLongs(buf, nextPath);
        writeLongs(buf, bestPath);
        writeLongs(buf, toBreak);
        writeLongs(buf, toPlace);
        writeLongs(buf, toWalkInto);
        writeLongs(buf, goalBoxes);
        writeLongs(buf, goalColumns);
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

    /**
     * 客户端收到后交给谁:主源码集看不见客户端类,所以由客户端源码集在启动时把它挂上(服务端上永远是空操作)。
     * 在客户端主线程上调。
     */
    public static volatile Consumer<PathDebugPayload> onClient = p -> {};

    /** 各加载器的下行包接收器调它。 */
    public static void handle(PathDebugPayload p) {
        onClient.accept(p);
    }
}
