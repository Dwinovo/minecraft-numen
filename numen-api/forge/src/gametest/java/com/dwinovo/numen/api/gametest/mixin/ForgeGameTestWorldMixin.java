package com.dwinovo.numen.api.gametest.mixin;

import java.util.Collection;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestBatch;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.server.Main;
import net.minecraft.server.WorldStem;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Forge 的 GameTest 服务器走原版建测试世界的那条路。这一代 Forge 的服务端入口在 GameTest 模式下拿 {@code server.properties} 建好的世界
 * ({@code WorldStem})直接 {@code new GameTestServer(...)},不经 {@code GameTestServer.create}:测试世界就成了普通地形,而测试夹具挂在
 * {@code create} 上的几件事(每次清空世界目录、超平坦主世界并进数据包定义的维度、种子)都不生效。这里把那一处构造换成
 * {@code create},和原版、Fabric 的测试服是同一个世界;入口已经建好的那份世界不用,关掉。
 */
@Mixin(Main.class)
public abstract class ForgeGameTestWorldMixin {

    /** 入口里构造 GameTest 服务器的那个 lambda(Forge 只在 GameTest 模式下走到这一处)。 */
    @Redirect(method = "lambda$main$4", at = @At(value = "NEW", target = "net/minecraft/gametest/framework/GameTestServer"))
    private static GameTestServer numen$createLikeVanilla(Thread thread, LevelStorageSource.LevelStorageAccess access,
            PackRepository packs, WorldStem fromProperties, Collection<GameTestBatch> batches, BlockPos spawn) {
        fromProperties.close();
        return GameTestServer.create(thread, access, packs, batches, spawn);
    }
}
