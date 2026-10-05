package com.dwinovo.numen.plugins.tlm.mixin;

import com.dwinovo.numen.plugins.tlm.MaidEvents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 女仆喂主人的那一口:"喂食"工作模式里,女仆走到主人身边,从自己的背包里挑一样,经这个方法让主人吃下去
 * ({@code finishUsingItem} 直接作用在主人身上)。吃东西走的是原版的 {@code Player.eat},不经过任何事件,车万女仆自己也
 * 不为这件事发事件——她的身体被别人喂了东西,只有这里知道。
 *
 * <p>进方法时记下喂的是什么(吃完那一叠就少了一个,空了就认不出),出方法时报。报不报她由 {@link MaidEvents#fed} 定。
 *
 * <p>{@link Pseudo}:没装车万女仆时目标类不存在,这份 mixin 静默跳过;装了而方法没了或签名变了,
 * {@code defaultRequire: 1} 让启动当场报错,不会悄悄不报。{@code remap = false}:{@code feed} 是车万女仆自己的方法,
 * 不在原版映射表里。
 */
@Pseudo
@Mixin(targets = "com.github.tartaricacid.touhoulittlemaid.entity.task.TaskFeedOwner", remap = false)
public abstract class TaskFeedOwnerMixin {

    @Unique
    private Item numen$eaten;

    @Inject(method = "feed", at = @At("HEAD"))
    private void numen$noteWhatIsFed(ItemStack stack, Player owner, CallbackInfoReturnable<ItemStack> cir) {
        numen$eaten = stack.getItem();
    }

    @Inject(method = "feed", at = @At("RETURN"))
    private void numen$tellHerSheWasFed(ItemStack stack, Player owner, CallbackInfoReturnable<ItemStack> cir) {
        MaidEvents.fed(owner, numen$eaten);
    }
}
