package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * Server → Client: a companion's 36 main backpack slots. Sent both as the answer to
 * {@link RequestStatePayload} (the Items tab asking) and unprompted whenever her
 * inventory actually changes, so the agent loop can state what she carries without
 * spending a turn on {@code get_self_status}. {@code loaded=false} means the body is
 * asleep in unloaded chunks (or not the requester's) — no contents.
 *
 * <p>{@code selectedSlot} and {@code offhand} ride along rather than being read off the
 * client-side entity: vanilla only syncs equipment for entities the client is tracking,
 * so once she walks out of view the hands would go blank while the backpack (pushed from
 * the server) stayed readable. Two fields buy one answer that is the same everywhere.
 *
 * <h2>为什么这里装的不只是背包</h2>
 * 饱食度、选中槽、副手早就在里面了 —— 它一直是<b>这具身体此刻的样子</b>,只是原来叫背包。
 * 身上的效果同理:模型每一轮都要读它才知道自己中没中毒、有没有抗性,而这类东西<b>一进对话
 * 历史就永远不会过期</b>,只能每次现挂。同一条通道、同一份快照,不必为每样状态另开一路。
 * 骑乘同理:她坐没坐在船上决定了"再点一次船"是不是废话、goto 会驾船还是走路,
 * 模型必须实时看见。{@code vehicleType} 空串 = 没骑任何东西,{@code vehicleId} 相应为 -1。
 */
public record NumenStatePayload(UUID uuid, boolean loaded, List<ItemStack> items,
                                List<ItemStack> craft, int foodLevel, float saturation,
                                int selectedSlot, ItemStack offhand,
                                List<MobEffectInstance> effects,
                                String vehicleType, int vehicleId)
        implements CustomPacketPayload {

    public static final Type<NumenStatePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "numen_state"));

    /** 手写而不是 {@code composite}:后者最多拼 6 个分量,这里有九个。 */
    public static final StreamCodec<RegistryFriendlyByteBuf, NumenStatePayload> STREAM_CODEC =
            StreamCodec.of(NumenStatePayload::write, NumenStatePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, NumenStatePayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.uuid());
        ByteBufCodecs.BOOL.encode(buf, p.loaded());
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.items());
        ItemStack.OPTIONAL_LIST_STREAM_CODEC.encode(buf, p.craft());
        ByteBufCodecs.VAR_INT.encode(buf, p.foodLevel());
        ByteBufCodecs.FLOAT.encode(buf, p.saturation());
        ByteBufCodecs.VAR_INT.encode(buf, p.selectedSlot());
        ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, p.offhand());
        MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.effects());
        ByteBufCodecs.STRING_UTF8.encode(buf, p.vehicleType());
        ByteBufCodecs.VAR_INT.encode(buf, p.vehicleId());
    }

    private static NumenStatePayload read(RegistryFriendlyByteBuf buf) {
        UUID uuid = UUIDUtil.STREAM_CODEC.decode(buf);
        boolean loaded = ByteBufCodecs.BOOL.decode(buf);
        List<ItemStack> items = ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf);
        List<ItemStack> craft = ItemStack.OPTIONAL_LIST_STREAM_CODEC.decode(buf);
        int foodLevel = ByteBufCodecs.VAR_INT.decode(buf);
        float saturation = ByteBufCodecs.FLOAT.decode(buf);
        int selectedSlot = ByteBufCodecs.VAR_INT.decode(buf);
        ItemStack offhand = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
        List<MobEffectInstance> effects =
                MobEffectInstance.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf);
        String vehicleType = ByteBufCodecs.STRING_UTF8.decode(buf);
        int vehicleId = ByteBufCodecs.VAR_INT.decode(buf);
        return new NumenStatePayload(uuid, loaded, items, craft, foodLevel, saturation,
                selectedSlot, offhand, effects, vehicleType, vehicleId);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client main thread. */
    public static void handle(NumenStatePayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.state.accept(p);
    }
}
