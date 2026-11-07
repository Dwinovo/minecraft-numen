package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.NumenTestInstance;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.gametest.framework.GameTestServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 测试服跑的只有我们登记的用例({@link NumenTestInstance}):原版数据包自带的测试实例(如 {@code minecraft:always_pass})不在选中的套件里,
 * 不跑,也不算进"全部通过"的条数。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestSelectionMixin {

    @WrapOperation(method = "evaluateTestsToRun", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/core/Registry;listElements()Ljava/util/stream/Stream;"))
    private <T> Stream<Holder.Reference<T>> numen$onlyOurs(Registry<T> registry, Operation<Stream<Holder.Reference<T>>> list) {
        return list.call(registry).filter(test -> test.value() instanceof NumenTestInstance);
    }
}
