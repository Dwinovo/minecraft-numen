package com.dwinovo.numen.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of a companion's backpack, fed by {@code NumenInventoryPayload}
 * and read by the Items tab. Other players' inventories aren't synced to clients,
 * so this only holds what an explicit request fetched. Client main thread only.
 */
public final class ClientNumenInventory {

    /** {@code loaded=false} = the body is asleep / not ours (no contents). foodLevel 0-20.
     *  {@code craft} is the 2×2 crafting menu: indices 0-3 = grid, index 4 = result (may be empty).
     *  {@code selectedSlot} indexes {@code items} for the main hand. */
    public record Snapshot(boolean loaded, List<ItemStack> items, List<ItemStack> craft,
                           int foodLevel, float saturation, int selectedSlot, ItemStack offhand,
                           long receivedAtMs) {

        /** 主手那一格;槽位越界(空快照)时给空栈。 */
        public ItemStack mainHand() {
            return selectedSlot >= 0 && selectedSlot < items.size()
                    ? items.get(selectedSlot)
                    : ItemStack.EMPTY;
        }
    }

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private ClientNumenInventory() {}

    public static void update(UUID uuid, Snapshot snapshot) {
        CACHE.put(uuid, snapshot);
    }

    public static Optional<Snapshot> get(UUID uuid) {
        return Optional.ofNullable(CACHE.get(uuid));
    }

    public static void clear() {
        CACHE.clear();
    }
}
