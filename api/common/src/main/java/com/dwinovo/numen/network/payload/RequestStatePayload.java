package com.dwinovo.numen.network.payload;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.network.NumenPayload;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Client → Server: "show me this companion's backpack." Other players' full
 * inventory isn't synced to clients (only equipment is), so the read-only Items
 * tab fetches it on demand. Answered with one {@link NumenStatePayload}.
 *
 * <p>Only the owner of a LOADED companion gets the contents; otherwise the reply
 * is {@code loaded=false} (asleep / not yours — no inventory oracle).
 */
public record RequestStatePayload(UUID uuid) implements NumenPayload {

    /** The 36 main backpack slots (hotbar + storage); equipment is already client-synced. */
    public static final int MAIN_SLOTS = 36;

    public static final ResourceLocation ID =
            new ResourceLocation(Constants.MOD_ID, "request_state");

    @Override
    public ResourceLocation id() {
        return ID;
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeUUID(uuid);
    }

    public static RequestStatePayload read(FriendlyByteBuf buf) {
        return new RequestStatePayload(buf.readUUID());
    }

    /** Server main thread. */
    public static void handle(RequestStatePayload p, ServerPlayer player) {
        NumenPlayer numen = NumenPlayer.findByUuid(player.level().getServer(), p.uuid());
        if (numen == null || !numen.isOwnedByPlayer(player.getUUID())) {
            Services.NETWORK.sendToPlayer(player, absent(p.uuid()));
            return;
        }
        Services.NETWORK.sendToPlayer(player, snapshot(numen));
    }

    /** 身体不在(睡在未加载区块 / 不是你的):没有内容可给。 */
    public static NumenStatePayload absent(java.util.UUID uuid) {
        return new NumenStatePayload(uuid, false, List.of(), List.of(), 0, 0f,
                0, ItemStack.EMPTY, List.of(), "", -1);
    }

    /**
     * 这具身体此刻带着什么。应答面板的请求和背包变化时的主动推送共用这一份——两条路给出
     * 不同的快照就等于两个真源。
     */
    public static NumenStatePayload snapshot(NumenPlayer numen) {
        Inventory inv = numen.getInventory();
        List<ItemStack> items = new ArrayList<>(MAIN_SLOTS);
        for (int i = 0; i < MAIN_SLOTS; i++) {
            items.add(inv.getItem(i).copy());
        }
        // The 2×2 crafting menu (vanilla InventoryMenu layout): slot 0 = result, slots 1-4 = grid.
        // Packed as [grid0, grid1, grid2, grid3, result] for the Items tab to mirror.
        List<ItemStack> craft = new ArrayList<>(5);
        for (int i = 1; i <= 4; i++) craft.add(numen.inventoryMenu.getSlot(i).getItem().copy());
        craft.add(numen.inventoryMenu.getSlot(0).getItem().copy());
        // 效果照抄一份:MobEffectInstance 是可变的(每 tick 自减),直接引用会让客户端读到
        // 一个还在动的对象。
        List<net.minecraft.world.effect.MobEffectInstance> effects = new ArrayList<>();
        for (var live : numen.getActiveEffects()) {
            effects.add(new net.minecraft.world.effect.MobEffectInstance(live));
        }
        // 骑乘随身照:类型按注册路径报,id 给 interact_entity 直接可用的实体号
        net.minecraft.world.entity.Entity vehicle = numen.getVehicle();
        String vehicleType = vehicle == null ? ""
                : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                        .getKey(vehicle.getType()).getPath();
        return new NumenStatePayload(numen.getUUID(), true, items, craft,
                numen.getFoodData().getFoodLevel(), numen.getFoodData().getSaturationLevel(),
                inv.selected, numen.getOffhandItem().copy(), effects,
                vehicleType, vehicle == null ? -1 : vehicle.getId());
    }
}
