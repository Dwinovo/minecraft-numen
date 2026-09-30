package com.dwinovo.numen.plugins.tlm.mixin;

import com.dwinovo.numen.plugins.tlm.MaidEvents;
import com.github.tartaricacid.touhoulittlemaid.entity.task.TaskFeedOwner;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 女仆喂主人的那一口:"喂食"工作模式里,女仆走到主人身边,从自己的背包里挑一样,经这个方法让主人吃下去
 * ({@code finishUsingItem} 直接作用在主人身上)。吃东西走的是原版的 {@code Player.eat},不经过任何事件,车万女仆自己也
 * 不为这件事发事件——她的身体被别人喂了东西,只有这里知道。
 *
 * <p>包着整个方法:先记下喂的是什么(吃完那一叠就少了一个,空了就认不出),吃完再报。报不报她由 {@link MaidEvents#fed} 定。
 *
 * <p>这份 mixin 配置只在车万女仆在场时挂({@code neoforge.mods.toml} 里的 {@code requiredMods});在场而方法没了或签名
 * 变了,{@code defaultRequire: 1} 让启动当场报错,不会悄悄不报。
 */
@Mixin(value = TaskFeedOwner.class, remap = false)
public abstract class TaskFeedOwnerMixin {

    @WrapMethod(method = "feed")
    private ItemStack numen$tellHerSheWasFed(ItemStack stack, Player owner, Operation<ItemStack> original) {
        Item eaten = stack.getItem();
        ItemStack left = original.call(stack, owner);
        MaidEvents.fed(owner, eaten);
        return left;
    }
}
