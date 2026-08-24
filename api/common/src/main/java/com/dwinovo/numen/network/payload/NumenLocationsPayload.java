package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server → Client: answer to {@link LocateNumenPayload}. One snapshot per
 * requested UUID — position, dimension and HP for owned + loaded companions,
 * {@code found=false} otherwise. The client drops them into
 * {@link ClientNumenLocations} for the roster panel / vitals strip to read.
 */
public record NumenLocationsPayload(List<Snapshot> snapshots) implements CustomPacketPayload {

    /**
     * Wire shape of one located (or not) companion. {@code loaded=false} with
     * {@code found=true} means "asleep in unloaded chunks at its last known
     * position" — position/dimension are valid, vitals are not.
     */
    public record Snapshot(UUID uuid, boolean found, boolean loaded,
                           double x, double y, double z,
                           String dimension, float hp, float maxHp) {

        public static Snapshot notFound(UUID uuid) {
            return new Snapshot(uuid, false, false, 0, 0, 0, "", 0, 0);
        }

        /** A live, ticking companion. */
        public static Snapshot live(UUID uuid, double x, double y, double z,
                                    String dimension, float hp, float maxHp) {
            return new Snapshot(uuid, true, true, x, y, z, dimension, hp, maxHp);
        }

        /** Unloaded — last known position from the persistent index. */
        public static Snapshot lastSeen(UUID uuid, double x, double y, double z, String dimension) {
            return new Snapshot(uuid, true, false, x, y, z, dimension, 0, 0);
        }

        void write(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            buf.writeBoolean(found);
            buf.writeBoolean(loaded);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeUtf(dimension, 256);
            buf.writeFloat(hp);
            buf.writeFloat(maxHp);
        }

        static Snapshot read(FriendlyByteBuf buf) {
            return new Snapshot(buf.readUUID(),
                    buf.readBoolean(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readUtf(256),
                    buf.readFloat(), buf.readFloat());
        }
    }

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "numen_locations");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        int n = Math.min(snapshots.size(), LocateNumenPayload.MAX_UUIDS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            snapshots.get(i).write(buf);
        }
    }

    public static NumenLocationsPayload read(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), LocateNumenPayload.MAX_UUIDS);
        List<Snapshot> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(Snapshot.read(buf));
        }
        return new NumenLocationsPayload(list);
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenLocationsPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.locations.accept(p);
    }
}
