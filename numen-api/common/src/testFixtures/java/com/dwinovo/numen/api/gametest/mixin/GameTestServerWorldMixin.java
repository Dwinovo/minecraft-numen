package com.dwinovo.numen.api.gametest.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 原版 GameTest 服务器建世界只用超平坦预设自带的三个维度,数据包里定义的维度被丢掉,种子也写死为 0。这里补上这两件,
 * 主世界仍是超平坦(测试场地放在里面),数据包定义的维度(如 {@code numen_test:terrain} 的真实地形)并进来,
 * 用例拿绝对坐标在那个维度里跑。种子由系统属性 {@code numen.gametest.seed} 指定,没给就是原来的 0。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerWorldMixin {

    /**
     * 建世界的那个 lambda(只有它收 LevelSettings 与 DataLoadContext)。合成方法的名字各加载器的开发环境不同(NeoForge 是
     * lambda$create$1,Fabric 的映射下是 method_40377),所以只按描述符选。
     */
    private static final String LOAD =
            "(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/server/WorldLoader$DataLoadContext;)"
                    + "Lnet/minecraft/server/WorldLoader$DataLoadOutput;";

    /** 预设烘出维度时,把数据包里加载好的维度作为已有的并进去(预设里没有的才用预设的)。 */
    @WrapOperation(
            method = LOAD,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)"
                            + "Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"))
    private static WorldDimensions.Complete numen$withDatapackDimensions(WorldDimensions preset, Registry<LevelStem> empty,
            Operation<WorldDimensions.Complete> bake, @Local(argsOnly = true) WorldLoader.DataLoadContext context) {
        return bake.call(preset, context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM));
    }

    @ModifyExpressionValue(
            method = LOAD,
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/gametest/framework/GameTestServer;WORLD_OPTIONS:Lnet/minecraft/world/level/levelgen/WorldOptions;"))
    private static WorldOptions numen$seed(WorldOptions original) {
        String seed = System.getProperty("numen.gametest.seed");
        return seed == null || seed.isBlank() ? original : new WorldOptions(Long.parseLong(seed.trim()), false, false);
    }
}
