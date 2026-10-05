package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.Belongings;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import net.minecraft.advancements.Advancement;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

/**
 * 她达成一个进度时,奖励(物品、经验)在 {@code award} 里当场发到她身上。原版没有"谁因为哪个进度得了什么"的事件,NeoForge 与
 * Fabric 各一套,common 不按加载器分支,所以挂在两边共有的这一处:前后各拍一张她身上的东西,变了就告诉她。没变的不说——
 * 配方解锁这类没有物品奖励的进度天天在达成。
 *
 * <p>前一张拍在开头、后一张在返回处对:这一代没有包住整个方法的注入,而 {@code award} 会重入(奖励带来的物品又触发别的进度),
 * 所以前一张按后进先出存着。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsRewardMixin {

    @Shadow
    private ServerPlayer player;

    /** 还没返回的 {@code award} 各自开头时她身上的样子;只有她自己的进度才存。 */
    @Unique
    private final ArrayDeque<Belongings> numen$before = new ArrayDeque<>();

    @Inject(method = "award", at = @At("HEAD"))
    private void numen$snapshot(Advancement advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (player instanceof NumenPlayer her) {
            numen$before.push(Belongings.of(her));
        }
    }

    @Inject(method = "award", at = @At("RETURN"))
    private void numen$rewarded(Advancement advancement, String criterion, CallbackInfoReturnable<Boolean> cir) {
        if (!(player instanceof NumenPlayer her)) {
            return;
        }
        String change = numen$before.pop().changeTo(her);
        if (!change.isEmpty()) {
            String id = advancement.getId().toString();
            NumenEvents.advancementReward(her, id,
                    advancement.getDisplay() == null ? id : advancement.getDisplay().getTitle().getString(), change);
        }
    }
}
