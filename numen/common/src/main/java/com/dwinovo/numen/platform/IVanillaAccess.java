package com.dwinovo.numen.platform;

import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * 内容要读、要调的几处原版私有成员。私有成员怎么够得着是平台的事(模组用 Mixin 访问器,Fabric 生产环境是中间名,
 * 不能按 Mojang 名反射),内容只说她要干什么。
 */
public interface IVanillaAccess {

    /** 鱼上钩的倒计时:有鱼在咬钩的窗口里时它落在原版定的那几刻内,窗口的判据由钓鱼任务自己定。 */
    int biteCountdown(FishingHook hook);

    /** 掉落物还要过几刻才让人捡(刚掉下来的东西有一小段冷却)。 */
    int pickupDelay(ItemEntity item);

    /** 界面同步给客户端的数据槽:机器进度、燃料、能量这些真屏幕画进度条读的整数。 */
    List<DataSlot> syncedDataSlots(AbstractContainerMenu menu);

    /**
     * 按当前格局重算合成结果槽,当场生效。它是结果槽唯一的写入处;原版的 {@code slotsChanged} 只是触发器,
     * 可被覆写成之后再算(FastWorkbench 一类),所以摆完格子要直接要这趟计算。{@code hint} 是已知的那条配方,省一次全表扫。
     */
    void recomputeCraftingResult(AbstractContainerMenu menu, Level level, Player player, CraftingContainer grid,
                                 ResultContainer result, RecipeHolder<CraftingRecipe> hint);
}
