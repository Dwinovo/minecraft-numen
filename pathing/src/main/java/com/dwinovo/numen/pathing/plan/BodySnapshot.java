package com.dwinovo.numen.pathing.plan;

import java.util.List;
import java.util.Objects;

import com.dwinovo.numen.pathing.world.BodyStats;

import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;

/**
 * 身体快照:规划要知道的身体的一切,由宿主在派发那一刻从真实的身体上抄下来。规划与执行复核读同一份形状,搜索线程只读。
 *
 * <p>摔落上限、能不能疾跑、能不能动方块这几条规则只写在这里,规格只能在其上收紧。
 *
 * @param stats                   第 0 层用的物理量(尺寸、迈步、起跳、交互距离、细雪托不托得住)
 * @param gameMode                游戏模式:创造模式瞬间挖掉、不摔伤;冒险与旁观模式动不了方块
 * @param health                  当前血量
 * @param safeFallDistance        属性 {@code safe_fall_distance}:摔这么高以内不掉血(原版 3)
 * @param fallDamageMultiplier    属性 {@code fall_damage_multiplier}(原版 1)
 * @param foodLevel               饱食度:原版高于 6 才能疾跑
 * @param waterMovementEfficiency 属性 {@code water_movement_efficiency}:0 是原版水里的步速,1 与陆上一样快
 * @param inventory               主背包 36 格的副本,下标就是槽位;挑工具看全背包
 * @param mining                  挖掘速度相关的属性与效果
 */
public record BodySnapshot(BodyStats stats, GameType gameMode, float health, double safeFallDistance,
                           double fallDamageMultiplier, int foodLevel, double waterMovementEfficiency,
                           List<ItemStack> inventory, Mining mining) {

    /** 摔完至少要留下的血量(三颗心):按血量推摔落上限时不把她摔到只剩一口气。 */
    static final float HEALTH_RESERVE = 6.0F;
    /** 摔落上限的搜索范围;创造模式不摔伤,上限就是它。 */
    private static final int NO_LIMIT = 4096;

    /**
     * 挖掘速度用到的身体属性与效果,照原版 {@code Player.getDestroySpeed} 取值。
     *
     * @param efficiency     属性 {@code mining_efficiency} 里不来自手上那件的部分(原版 0);手上那件的效率附魔由挑工具时按
     *                       那件自己的修饰符加上
     * @param breakSpeed     属性 {@code block_break_speed}(原版 1)
     * @param submergedSpeed 属性 {@code submerged_mining_speed}(原版 0.2,水下速掘把它提到 1)
     * @param haste          急迫与潮涌能量里较高的等级(amplifier),没有是 -1
     * @param fatigue        挖掘疲劳的等级(amplifier),没有是 -1
     */
    public record Mining(double efficiency, double breakSpeed, double submergedSpeed, int haste, int fatigue) {

        /** 原版玩家不带任何效果时的取值。 */
        public static final Mining VANILLA = new Mining(0, 1, 0.2, -1, -1);
    }

    public BodySnapshot {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(gameMode, "gameMode");
        Objects.requireNonNull(mining, "mining");
        inventory = inventory.stream().map(ItemStack::copy).toList();
    }

    public boolean creative() {
        return gameMode.isCreative();
    }

    /** 背包里有一桶水:摔不起的坠落可以倒水接住。 */
    public boolean carriesWaterBucket() {
        for (ItemStack stack : inventory) {
            if (stack.is(Items.WATER_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    /** 能不能挖、能不能放:冒险与旁观模式不能(原版 {@code GameType.isBlockPlacingRestricted})。 */
    public boolean mayEdit() {
        return !gameMode.isBlockPlacingRestricted();
    }

    /** 能不能疾跑:饱食度高于 6,或能飞的创造模式(原版 {@code LocalPlayer.hasEnoughFoodToStartSprinting})。 */
    public boolean canSprint() {
        return foodLevel > 6 || creative();
    }

    /**
     * 从 {@code height} 格高处落到硬地上掉几点血,照原版 {@code LivingEntity.calculateFallDamage}:超出安全高度的部分乘以倍率
     * 后向上取整。落点方块自己的减伤(干草块、床)不算。创造模式不摔伤。
     */
    public int fallDamage(double height) {
        if (creative()) {
            return 0;
        }
        return Math.max(0, Mth.ceil((height - safeFallDistance) * fallDamageMultiplier));
    }

    /**
     * 摔落上限:落到硬地上最多能落几格——摔不疼的高度都行,要掉血的只到摔完还留 {@link #HEALTH_RESERVE} 点血为止。
     * 这是全模块唯一的摔落上限;路线规格的无水落差只能比它更紧。
     */
    public int maxFall() {
        int height = 0;
        while (height < NO_LIMIT) {
            int damage = fallDamage(height + 1);
            if (damage > 0 && health - damage < HEALTH_RESERVE) {
                return height;
            }
            height++;
        }
        return NO_LIMIT;
    }
}
