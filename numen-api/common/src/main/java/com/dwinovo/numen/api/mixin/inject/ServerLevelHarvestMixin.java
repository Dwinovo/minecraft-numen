package com.dwinovo.numen.api.mixin.inject;

import com.dwinovo.numen.api.entity.Harvest;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 一只实体真进了世界的那一刻交给 {@link Harvest}:她的手正挖碎一格时生成的掉落物在这里被记下。方块本身的掉落、连带碎掉的(火把、
 * 箱子里的东西)最后都经这里进世界,两个加载器一样;只挂 {@code popResource} 会漏掉箱子里的东西。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelHarvestMixin {

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void numen$dropped(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            Harvest.added(entity);
        }
    }
}
