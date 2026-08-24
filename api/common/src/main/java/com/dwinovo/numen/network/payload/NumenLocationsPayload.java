package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

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

        // StreamCodec.composite 上限 12 字段(26.1.2),装下本 record 的 9 个;线格式与
        // 逐字段手写完全一致(同序同码,writeUtf(256) ≡ stringUtf8(256))。
        static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> CODEC =
                StreamCodec.composite(
                        UUIDUtil.STREAM_CODEC, Snapshot::uuid,
                        ByteBufCodecs.BOOL, Snapshot::found,
                        ByteBufCodecs.BOOL, Snapshot::loaded,
                        ByteBufCodecs.DOUBLE, Snapshot::x,
                        ByteBufCodecs.DOUBLE, Snapshot::y,
                        ByteBufCodecs.DOUBLE, Snapshot::z,
                        ByteBufCodecs.stringUtf8(256), Snapshot::dimension,
                        ByteBufCodecs.FLOAT, Snapshot::hp,
                        ByteBufCodecs.FLOAT, Snapshot::maxHp,
                        Snapshot::new);
    }

    public static final Type<NumenLocationsPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath(Constants.MOD_ID, "numen_locations"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NumenLocationsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Snapshot.CODEC.apply(ByteBufCodecs.list(LocateNumenPayload.MAX_UUIDS)),
                    NumenLocationsPayload::snapshots,
                    NumenLocationsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(NumenLocationsPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.locations.accept(p);
    }
}
