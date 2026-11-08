package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.BatchStart;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版每个实体自带一份不定种子的随机数(烟花的寿命、怪的 AI、掉落物的初速都从它掷)。测试服里实体造出来的那一刻,按它在这一批里的
 * 编号重新定种子({@link BatchStart#entitySeed}):这一批里第几个造出来的实体,每次开服掷出的点数都一样。造的时候原版已经用这份随机数
 * 生成了 UUID,那一步不动:UUID 照旧各不相同。
 */
@Mixin(Entity.class)
public abstract class EntityRandomMixin {

    @Shadow
    @Final
    protected RandomSource random;

    @Shadow
    public abstract int getId();

    @Inject(method = "<init>", at = @At("RETURN"))
    private void numen$seedByBatchId(CallbackInfo ci) {
        random.setSeed(BatchStart.entitySeed(getId()));
    }
}
