package com.dwinovo.numen.agent.request;

import com.dwinovo.numen.network.payload.NumenStatePayload;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 主人这一侧收到的她的身体状态:服务端经 {@link NumenStatePayload} 推来的一份,记下收到的时刻。背包块、效果块照它渲染
 * ({@link RuntimeState}),物品页照它画。
 *
 * <p>{@code loaded=false} = the body is asleep / not ours (no contents). foodLevel 0-20.
 * {@code craft} is the 2×2 crafting menu: indices 0-3 = grid, index 4 = result (may be empty).
 * {@code selectedSlot} indexes {@code items} for the main hand.
 * {@code vehicleType} 空串 = 没骑任何东西({@code vehicleId} 相应为 -1)。
 * {@code bodyState} 是插件从身体上读的状态片段,服务端拼好的整段;空串 = 没有。
 */
public record BodySnapshot(boolean loaded, List<ItemStack> items, List<ItemStack> craft,
                           int foodLevel, float saturation, int selectedSlot, ItemStack offhand,
                           List<MobEffectInstance> effects,
                           String vehicleType, int vehicleId, String bodyState,
                           long receivedAtMs) {

    /** 收到的这一包,盖上收到的时刻。 */
    public static BodySnapshot of(NumenStatePayload p, long receivedAtMs) {
        return new BodySnapshot(p.loaded(), p.items(), p.craft(), p.foodLevel(), p.saturation(),
                p.selectedSlot(), p.offhand(), p.effects(), p.vehicleType(), p.vehicleId(), p.bodyState(),
                receivedAtMs);
    }

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
    public int remainingTicks(MobEffectInstance effect, long nowMs) {
        if (effect.isInfiniteDuration()) {
            return -1;
        }
        long elapsed = Math.max(0L, nowMs - receivedAtMs) / 50L;
        return (int) Math.max(0L, effect.getDuration() - elapsed);
    }
}
