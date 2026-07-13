package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.agent.NumenRoster;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> Client: the roster of companions this player owns (UUID + name).
 * The companion body is a fake {@code ServerPlayer}, so the client can't be
 * "enrolled" by right-clicking a Mob any more -- the server is the authority on
 * which companions exist. Pushed on owner login (after their dormant companions
 * respawn) and right after a fresh summon, so the client's {@link NumenRoster}
 * panel always reflects the truth without the player having to seek each body
 * out physically.
 */
public record CompanionListPayload(List<Entry> companions) {

    /** Cap defends against absurd input; nobody owns hundreds of companions. */
    public static final int MAX = 64;

    /** One companion's roster line. */
    public record Entry(UUID uuid, String name) {
        public void encode(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            buf.writeUtf(name);
        }

        public static Entry decode(FriendlyByteBuf buf) {
            return new Entry(buf.readUUID(), buf.readUtf());
        }
    }

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "companion_list");

    public void encode(FriendlyByteBuf buf) {
        List<Entry> list = companions;
        int size = Math.min(list.size(), MAX);
        buf.writeVarInt(size);
        for (int i = 0; i < size; i++) {
            list.get(i).encode(buf);
        }
    }

    public static CompanionListPayload decode(FriendlyByteBuf buf) {
        int size = buf.readVarInt();
        List<Entry> list = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            list.add(Entry.decode(buf));
        }
        return new CompanionListPayload(list);
    }

    /** Client-side handler. Runs on the client main thread (network layer arranges that). */
    public static void handle(CompanionListPayload p) {
        List<NumenRoster.Entry> snapshot = new ArrayList<>();
        for (Entry e : p.companions()) {
            snapshot.add(new NumenRoster.Entry(e.uuid(), e.name()));
        }
        NumenRoster.instance().replaceAll(snapshot);
    }
}
