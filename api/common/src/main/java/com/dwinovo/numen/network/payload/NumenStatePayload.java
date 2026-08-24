package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
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

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "numen_state");

    /** 物品清单的护栏:36 主格 + 2×2 合成栏,64 远超实际上限,防的是恶意长度。 */
    private static final int MAX_ITEMS = 64;
    private static final int MAX_EFFECTS = 64;

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
        buf.writeBoolean(loaded);
        writeItems(buf, items);
        writeItems(buf, craft);
        buf.writeVarInt(foodLevel);
        buf.writeFloat(saturation);
        buf.writeVarInt(selectedSlot);
        buf.writeItem(offhand);
        // 效果按 NBT 走:1.20.4 没有 MobEffectInstance 的流编解码器,save/load 是它的原生序列化
        int en = Math.min(effects.size(), MAX_EFFECTS);
        buf.writeVarInt(en);
        for (int i = 0; i < en; i++) {
            buf.writeNbt(effects.get(i).save(new CompoundTag()));
        }
        buf.writeUtf(vehicleType);
        buf.writeVarInt(vehicleId);
    }

    public static NumenStatePayload read(FriendlyByteBuf buf) {
        UUID uuid = buf.readUUID();
        boolean loaded = buf.readBoolean();
        List<ItemStack> items = readItems(buf);
        List<ItemStack> craft = readItems(buf);
        int foodLevel = buf.readVarInt();
        float saturation = buf.readFloat();
        int selectedSlot = buf.readVarInt();
        ItemStack offhand = buf.readItem();
        int en = Math.min(buf.readVarInt(), MAX_EFFECTS);
        List<MobEffectInstance> effects = new ArrayList<>(en);
        for (int i = 0; i < en; i++) {
            CompoundTag tag = buf.readNbt();
            MobEffectInstance e = tag == null ? null : MobEffectInstance.load(tag);
            if (e != null) {
                effects.add(e);
            }
        }
        String vehicleType = buf.readUtf();
        int vehicleId = buf.readVarInt();
        return new NumenStatePayload(uuid, loaded, items, craft, foodLevel, saturation,
                selectedSlot, offhand, effects, vehicleType, vehicleId);
    }

    private static void writeItems(FriendlyByteBuf buf, List<ItemStack> list) {
        int n = Math.min(list.size(), MAX_ITEMS);
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeItem(list.get(i));
        }
    }

    private static List<ItemStack> readItems(FriendlyByteBuf buf) {
        int n = Math.min(buf.readVarInt(), MAX_ITEMS);
        List<ItemStack> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(buf.readItem());
        }
        return list;
    }

    /** Client main thread. */
    public static void handle(NumenStatePayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.state.accept(p);
    }
}
