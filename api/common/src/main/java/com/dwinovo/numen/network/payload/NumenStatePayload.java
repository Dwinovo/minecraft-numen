package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import com.dwinovo.numen.network.Wire;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Server → Client: a companion's 36 main backpack slots. Sent both as the answer to
 * {@link RequestStatePayload} (the Items tab asking) and unprompted whenever her
 * inventory actually changes, so the agent loop can state what she carries without
 * spending a turn on {@code status_self}. {@code loaded=false} means the body is
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
 * 骑乘同理:她坐没坐在船上决定了"再点一次船"是不是废话、numen.move.go 会驾船还是走路,
 * 模型必须实时看见。{@code vehicleType} 空串 = 没骑任何东西,{@code vehicleId} 相应为 -1。
 * 身体状态片段({@code bodyState}:打头的 {@code <worn>} 与插件经 {@code NumenApi.contributeBodyState} 读的)同理:
 * 身体上的事实,同一份快照带过去;空串 = 没有插件要说什么。
 *
 * <h2>装不下一个包时</h2>
 * 身体状态片段是插件给的,物品带着任意的组件(写满的书、装满的潜影盒),长短都不归这个包定。整包装不下时
 * ({@link #shrunk})先把身体状态换成一句说明;还装不下,背包也不带,说明里一并交代——她读到的是"这次没送到",
 * 不是一个空背包。
 */
public record NumenStatePayload(UUID uuid, boolean loaded, List<ItemStack> items,
                                List<ItemStack> craft, int foodLevel, float saturation,
                                int selectedSlot, ItemStack offhand,
                                List<MobEffectInstance> effects,
                                String vehicleType, int vehicleId, String bodyState)
        implements CustomPacketPayload, Wire.Oversized<NumenStatePayload> {

    public static final ResourceLocation ID = new ResourceLocation(Constants.MOD_ID, "numen_state");

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
        Wire.writeText(buf, vehicleType);
        buf.writeVarInt(vehicleId);
        Wire.writeText(buf, bodyState);
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
        String vehicleType = Wire.readText(buf);
        int vehicleId = buf.readVarInt();
        String bodyState = Wire.readText(buf);
        return new NumenStatePayload(uuid, loaded, items, craft, foodLevel, saturation,
                selectedSlot, offhand, effects, vehicleType, vehicleId, bodyState);
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

    @Override
    public NumenStatePayload shrunk(Predicate<NumenStatePayload> fits, int bytes, int budget) {
        NumenStatePayload lighter = new NumenStatePayload(uuid, loaded, items, craft, foodLevel, saturation,
                selectedSlot, offhand, effects, vehicleType, vehicleId, "<not_delivered>"
                + Wire.TO_CLIENT.tooBig("Your body state", bytes) + ", so it is left out this time.</not_delivered>");
        if (fits.test(lighter)) {
            return lighter;
        }
        List<ItemStack> none = java.util.Collections.nCopies(items.size(), ItemStack.EMPTY);
        return new NumenStatePayload(uuid, loaded, none, java.util.Collections.nCopies(craft.size(), ItemStack.EMPTY),
                foodLevel, saturation, selectedSlot, ItemStack.EMPTY, effects, vehicleType, vehicleId,
                "<not_delivered>" + Wire.TO_CLIENT.tooBig("Your body state with your inventory", bytes)
                        + ", so both are left out this time: the empty slots here are not your inventory."
                        + "</not_delivered>");
    }

    /** Client main thread. */
    public static void handle(NumenStatePayload p) {
        com.dwinovo.numen.network.ClientPayloadSink.state.accept(p);
    }
}
