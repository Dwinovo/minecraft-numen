package com.dwinovo.numen.core.mixin;

import com.dwinovo.numen.core.act.Drops;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 一只实体真进了世界的那一刻交给 {@link Drops}:正挖着一格时生成的掉落物在这里被认领。方块本身的掉落、连带碎掉的(火把、箱子里的
 * 东西)最后都经这里进世界,两个加载器一样;只挂 {@code popResource} 会漏掉箱子里的东西。
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelDropsMixin {

    @Inject(method = "addFreshEntity", at = @At("RETURN"))
    private void numen$claimDrop(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            Drops.added(entity);
        }
    }
}
