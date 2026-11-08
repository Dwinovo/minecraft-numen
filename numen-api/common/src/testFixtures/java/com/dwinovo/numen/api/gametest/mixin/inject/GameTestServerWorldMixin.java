package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.TerrainWorld;
import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.WorldLoader;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 原版 GameTest 服务器建世界只用超平坦预设自带的三个维度,数据包里定义的维度被丢掉,种子也写死为 0。这里补上这两件,
 * 主世界仍是超平坦(测试场地放在里面),数据包定义的维度(如 {@code numen_test:terrain} 的真实地形)并进来,
 * 用例拿绝对坐标在那个维度里跑。种子由系统属性 {@code numen.gametest.seed} 指定,没给就是原来的 0。
 * 世界目录每次从干净开始(见 {@link TerrainWorld}),原版 GameTest 服务器复用上一次的世界目录。
 *
 * <p>只用 Mixin 自己的注入(重定向的处理方法可以带上被注入方法的参数):这一代 Forge 不带 MixinExtras。Forge 的入口本来不经
 * {@code create},由它那边的 {@code ForgeGameTestWorldMixin} 接回这条路。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerWorldMixin {

    @Shadow
    @Final
    private static WorldOptions WORLD_OPTIONS;

    /**
     * 建世界的那个 lambda(只有它收 LevelSettings 与 DataLoadContext)。合成方法的名字各加载器的开发环境不同(Fabric 的映射下是
     * method_40377),所以只按描述符选。
     */
    private static final String LOAD =
            "(Lnet/minecraft/world/level/LevelSettings;Lnet/minecraft/server/WorldLoader$DataLoadContext;)"
                    + "Lnet/minecraft/server/WorldLoader$DataLoadOutput;";

    /** 建世界之前:清世界目录。 */
    @Inject(method = "create", at = @At("HEAD"))
    private static void numen$freshWorld(Thread thread, LevelStorageSource.LevelStorageAccess access, PackRepository packs,
            Collection<TestFunction> tests, BlockPos spawn, CallbackInfoReturnable<GameTestServer> cir) {
        TerrainWorld.prepare(access.getLevelPath(LevelResource.ROOT));
    }

    /** 预设烘出维度时,把数据包里加载好的维度作为已有的并进去(预设里没有的才用预设的)。 */
    @Redirect(
            method = LOAD,
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/levelgen/WorldDimensions;bake(Lnet/minecraft/core/Registry;)"
                            + "Lnet/minecraft/world/level/levelgen/WorldDimensions$Complete;"))
    private static WorldDimensions.Complete numen$withDatapackDimensions(WorldDimensions preset, Registry<LevelStem> empty,
            LevelSettings settings, WorldLoader.DataLoadContext context) {
        return preset.bake(context.datapackDimensions().registryOrThrow(Registries.LEVEL_STEM));
    }

    /** 取预设的世界选项那一下(GETSTATIC,操作码 178):给了种子就换成带那个种子的。 */
    @Redirect(
            method = LOAD,
            at = @At(value = "FIELD", opcode = 178,
                    target = "Lnet/minecraft/gametest/framework/GameTestServer;WORLD_OPTIONS:Lnet/minecraft/world/level/levelgen/WorldOptions;"))
    private static WorldOptions numen$seed() {
        String seed = System.getProperty("numen.gametest.seed");
        return seed == null || seed.isBlank() ? WORLD_OPTIONS : new WorldOptions(Long.parseLong(seed.trim()), false, false);
    }
}
