package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.GameTestRegistration;
import com.mojang.serialization.Decoder;
import java.util.Map;
import net.minecraft.core.WritableRegistry;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 原版从数据包读完一个注册表、还没冻结时,交给 {@link GameTestRegistration} 加上我们的测试环境与测试实例。 */
@Mixin(RegistryDataLoader.class)
public abstract class GameTestRegistrationMixin {

    @Inject(method = "loadContentsFromManager", at = @At("TAIL"))
    private static void numen$addOurs(ResourceManager resources, RegistryOps.RegistryInfoLookup lookup, WritableRegistry<?> registry,
            Decoder<?> decoder, Map<ResourceKey<?>, Exception> errors, CallbackInfo ci) {
        GameTestRegistration.loaded(registry);
    }
}
