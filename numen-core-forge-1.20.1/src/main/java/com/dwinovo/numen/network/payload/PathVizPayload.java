package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.path.ClientPathViz;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> Client: the companion's current pathfinding plan, for the in-world
 * path overlay (Baritone's {@code PathRenderer}, ported to our server-authored
 * model). Baritone renders client-side from its own {@code PathingBehavior};
 * our path lives on the server, so the body pushes it to the owner whenever it
 * (re)plans a segment, and pushes an EMPTY one (all lists empty, no goal) to
 * clear the overlay when the path ends.
 *
 * <ul>
 *   <li>{@code nodes} -- the path positions (feet cells); drawn as a red poly-line.</li>
 *   <li>{@code toBreak} -- blocks the path will dig; drawn as red boxes.</li>
 *   <li>{@code toPlace} -- scaffold blocks the path will place; drawn as green boxes.</li>
 *   <li>{@code targets} -- the goal cell(s); drawn as a green box (absent while clearing).</li>
 * </ul>
 */
public record PathVizPayload(UUID companion,
                             ResourceLocation dimension,
                             List<BlockPos> nodes,
                             List<BlockPos> toBreak,
                             List<BlockPos> toPlace,
                             List<BlockPos> targets) {

    /** Cap per list -- paths are trimmed well below this; defends against absurd input. */
    public static final int MAX = 512;

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "path_viz");

    public void encode(FriendlyByteBuf buf) {
        buf.writeUUID(companion);
        buf.writeResourceLocation(dimension);
        encodeBlockPosList(buf, nodes);
        encodeBlockPosList(buf, toBreak);
        encodeBlockPosList(buf, toPlace);
        encodeBlockPosList(buf, targets);
    }

    public static PathVizPayload decode(FriendlyByteBuf buf) {
        return new PathVizPayload(
                buf.readUUID(),
                buf.readResourceLocation(),
                decodeBlockPosList(buf),
                decodeBlockPosList(buf),
                decodeBlockPosList(buf),
                decodeBlockPosList(buf));
    }

    private static void encodeBlockPosList(FriendlyByteBuf buf, List<BlockPos> list) {
        int size = Math.min(list.size(), MAX);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            buf.writeBlockPos(list.get(i));
        }
    }

    private static List<BlockPos> decodeBlockPosList(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<BlockPos> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(buf.readBlockPos());
        }
        return list;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(PathVizPayload p) {
        ClientPathViz.accept(p);
    }
}
