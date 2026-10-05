package com.dwinovo.numen.mixin;

import com.dwinovo.numen.entity.Belongings;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * 她达成一个进度时,奖励(物品、经验)在 {@code award} 里当场发到她身上。原版没有"谁因为哪个进度得了什么"的事件,NeoForge 与
 * Fabric 各一套,common 不按加载器分支,所以挂在两边共有的这一处:前后各拍一张她身上的东西,变了就告诉她。没变的不说——
 * 配方解锁这类没有物品奖励的进度天天在达成。
 */
@Mixin(PlayerAdvancements.class)
public abstract class PlayerAdvancementsRewardMixin {

    @Shadow
    private ServerPlayer player;

    @WrapMethod(method = "award")
    private boolean numen$rewarded(AdvancementHolder holder, String criterion, Operation<Boolean> original) {
        if (!(player instanceof NumenPlayer her)) {
            return original.call(holder, criterion);
        }
        Belongings before = Belongings.of(her);
        boolean awarded = original.call(holder, criterion);
        String change = before.changeTo(her);
        if (!change.isEmpty()) {
            String id = holder.id().toString();
            NumenEvents.advancementReward(her, id,
                    holder.value().display().map(d -> d.getTitle().getString()).orElse(id), change);
        }
        return awarded;
    }
}
