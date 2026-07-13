package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.ClientNumenLocations;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> Client: answer to {@link LocateNumenPayload}. One snapshot per
 * requested UUID -- position, dimension and HP for owned + loaded companions,
 * {@code found=false} otherwise. The client drops them into
 * {@link ClientNumenLocations} for the roster panel / vitals strip to read.
 */
public record NumenLocationsPayload(List<Snapshot> snapshots) {

    /**
     * Wire shape of one located (or not) companion. {@code loaded=false} with
     * {@code found=true} means "asleep in unloaded chunks at its last known
     * position" -- position/dimension are valid, vitals are not.
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

        /** Unloaded -- last known position from the persistent index. */
        public static Snapshot lastSeen(UUID uuid, double x, double y, double z, String dimension) {
            return new Snapshot(uuid, true, false, x, y, z, dimension, 0, 0);
        }

        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            buf.writeBoolean(found);
            buf.writeBoolean(loaded);
            buf.writeDouble(x);
            buf.writeDouble(y);
            buf.writeDouble(z);
            buf.writeUtf(dimension);
            buf.writeFloat(hp);
            buf.writeFloat(maxHp);
        }

        public static Snapshot decode(FriendlyByteBuf buf) {
            return new Snapshot(
                    buf.readUUID(),
                    buf.readBoolean(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(),
                    buf.readUtf(),
                    buf.readFloat(), buf.readFloat());
        }
    }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "numen_locations");

    public void encode(FriendlyByteBuf buf) {
        int size = Math.min(snapshots.size(), LocateNumenPayload.MAX_UUIDS);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            snapshots.get(i).encode(buf);
        }
    }

    public static NumenLocationsPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Snapshot> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(Snapshot.decode(buf));
        }
        return new NumenLocationsPayload(list);
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenLocationsPayload p) {
        long now = System.currentTimeMillis();
        for (Snapshot s : p.snapshots()) {
            ClientNumenLocations.update(s.uuid(), new ClientNumenLocations.Snapshot(
                    s.found(), s.loaded(), s.x(), s.y(), s.z(), s.dimension(), s.hp(), s.maxHp(), now));
        }
    }
}
