package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: a companion's 36 main backpack slots. Sent both as the answer to
 * {@link RequestInventoryPayload} (the Items tab asking) and unprompted whenever her
 * inventory actually changes, so the agent loop can state what she carries without
 * spending a turn on {@code get_self_status}. {@code loaded=false} means the body is
 * asleep in unloaded chunks (or not the requester's) — no contents.
 *
 * <p>{@code selectedSlot} and {@code offhand} ride along rather than being read off the
 * client-side entity: vanilla only syncs equipment for entities the client is tracking,
 * so once she walks out of view the hands would go blank while the backpack (pushed from
 * the server) stayed readable. Two fields buy one answer that is the same everywhere.
 */
public record NumenInventoryPayload(UUID uuid, boolean loaded, List<ItemStack> items,
                                    List<ItemStack> craft, int foodLevel, float saturation,
                                    int selectedSlot, ItemStack offhand)
        implements CustomPacketPayload {

    public static final Type<NumenInventoryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_inventory"));

    /** 手写而不是 {@code composite}:后者最多拼 6 个分量,这里有 8 个。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, NumenInventoryPayload> STREAM_CODEC =
            StreamCodec.of(NumenInventoryPayload::write, NumenInventoryPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, NumenInventoryPayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.uuid());
        ByteBufCodecs.BOOL.encode(buf, p.loaded());
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.items());
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.craft());
        ByteBufCodecs.VAR_INT.encode(buf, p.foodLevel());
        ByteBufCodecs.FLOAT.encode(buf, p.saturation());
        ByteBufCodecs.VAR_INT.encode(buf, p.selectedSlot());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.offhand());
    }

    private static NumenInventoryPayload read(RegistryFriendlyByteBuf buf) {
        UUID uuid = UUIDUtil.STREAM_CODEC.decode(buf);
        boolean loaded = ByteBufCodecs.BOOL.decode(buf);
        List<ItemStack> items = ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf);
        List<ItemStack> craft = ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf);
        int foodLevel = ByteBufCodecs.VAR_INT.decode(buf);
        float saturation = ByteBufCodecs.FLOAT.decode(buf);
        int selectedSlot = ByteBufCodecs.VAR_INT.decode(buf);
        ItemStack offhand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        return new NumenInventoryPayload(uuid, loaded, items, craft, foodLevel, saturation,
                selectedSlot, offhand);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(NumenInventoryPayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.inventory.accept(p);
    }
}
