package com.dwinovo.numen.core.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "com.dwinovo.numen.core.task.RangedAttackCompanionTask")
public interface RangedAttackTaskAccessor {
    @Invoker("clearTarget")
    void numen$clearTarget();
}
