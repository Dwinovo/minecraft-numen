package com.dwinovo.numen.client.data;

import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Client-side cache of a companion's backpack, fed by {@code NumenStatePayload}
 * and read by the Items tab. Other players' inventories aren't synced to clients,
 * so this only holds what an explicit request fetched. Client main thread only.
 */
public final class ClientNumenState {

    /** {@code loaded=false} = the body is asleep / not ours (no contents). foodLevel 0-20.
     *  {@code craft} is the 2×2 crafting menu: indices 0-3 = grid, index 4 = result (may be empty).
     *  {@code selectedSlot} indexes {@code items} for the main hand.
     *  {@code vehicleType} 空串 = 没骑任何东西({@code vehicleId} 相应为 -1)。 */
    public record Snapshot(boolean loaded, List<ItemStack> items, List<ItemStack> craft,
                           int foodLevel, float saturation, int selectedSlot, ItemStack offhand,
                           List<net.minecraft.world.effect.MobEffectInstance> effects,
                           String vehicleType, int vehicleId,
                           long receivedAtMs) {

        /** 主手那一格;槽位越界(空快照)时给空栈。 */
        public ItemStack mainHand() {
            return selectedSlot >= 0 && selectedSlot < items.size()
                    ? items.get(selectedSlot)
                    : ItemStack.EMPTY;
        }

        /**
         * 这一刻还剩几刻。<b>服务端只在"多了/少了/升级了"时才重推</b>,所以包里那个时长是
         * 收到那一刻的值;效果是确定性的每刻自减,按收到至今的时间往下扣就是真值。
         *
         * @return 扣完余量,已经过期的给 0
         */
        public int remainingTicks(net.minecraft.world.effect.MobEffectInstance effect,
                                  long nowMs) {
            if (effect.isInfiniteDuration()) {
                return -1;
            }
            long elapsed = Math.max(0L, nowMs - receivedAtMs) / 50L;
            return (int) Math.max(0L, effect.getDuration() - elapsed);
        }
    }

    private static final Map<UUID, Snapshot> CACHE = new HashMap<>();

    private ClientNumenState() {}

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
