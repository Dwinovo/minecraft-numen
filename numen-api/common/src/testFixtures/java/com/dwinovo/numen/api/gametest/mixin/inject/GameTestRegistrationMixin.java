package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.GameTestRegistration;
import java.util.Map;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryLoadTask;
import net.minecraft.resources.ResourceKey;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版从数据包读完一个注册表、冻结之前(26.x 每个注册表一个装载任务,读完了在 {@code freezeRegistry} 里冻上),交给
 * {@link GameTestRegistration} 加上我们的测试环境与测试实例。
 */
@Mixin(RegistryLoadTask.class)
public abstract class GameTestRegistrationMixin<T> {

    @Shadow
    @Final
    private WritableRegistry<T> registry;

    @Inject(method = "freezeRegistry", at = @At("HEAD"))
    private void numen$addOurs(Map<ResourceKey<?>, Exception> loadingErrors, CallbackInfoReturnable<Boolean> cir) {
        GameTestRegistration.loaded(registry);
    }
}
