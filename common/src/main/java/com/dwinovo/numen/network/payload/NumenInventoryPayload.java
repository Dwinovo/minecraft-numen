package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.client.data.ClientNumenInventory;
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
 * Server → Client: a companion's 36 main backpack slots, answering
 * {@link RequestInventoryPayload}. {@code loaded=false} means the body is asleep
 * in unloaded chunks (or not the requester's) — no contents. Dropped into
 * {@link ClientNumenInventory} for the Items tab to render read-only.
 */
public record NumenInventoryPayload(UUID uuid, boolean loaded, List<ItemStack> items,
                                    List<ItemStack> craft, int foodLevel, float saturation)
        implements CustomPacketPayload {

    public static final Type<NumenInventoryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_inventory"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NumenInventoryPayload> STREAM_CODEC =
            StreamCodec.composite(
                    UUIDUtil.STREAM_CODEC, NumenInventoryPayload::uuid,
                    ByteBufCodecs.BOOL, NumenInventoryPayload::loaded,
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC, NumenInventoryPayload::items,
                    ItemStack.OPTIONAL_LIST_STREAM_CODEC, NumenInventoryPayload::craft,
                    ByteBufCodecs.VAR_INT, NumenInventoryPayload::foodLevel,
                    ByteBufCodecs.FLOAT, NumenInventoryPayload::saturation,
                    NumenInventoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(NumenInventoryPayload p) {
        ClientNumenInventory.update(p.uuid(), new ClientNumenInventory.Snapshot(
                p.loaded(), p.items(), p.craft(), p.foodLevel(), p.saturation(), System.currentTimeMillis()));
    }
}
