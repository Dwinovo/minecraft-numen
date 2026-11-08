package com.dwinovo.numen.api.gametest.mixin.inject;

import com.dwinovo.numen.api.gametest.BatchStart;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 这一代原版给新玩家挑出生点({@code ServerPlayer.fudgeSpawnLocation},在出生点周围散开)用的是一份当场新建、种子不定的随机数,
 * 每次开服落脚的格子都不同。扮主人的玩家就站在那里,他身边哪几个区块在走刻跟着变,而每个走刻的区块每刻都掷一次世界随机数(降雪结冰),
 * 后面掉落物的初速、战利品就都错开了。测试服里这份随机数按这个玩家在这一批里的编号定种子({@link BatchStart#entitySeed}),
 * 和实体自带的随机数同一个口径。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerSpawnMixin {

    @Redirect(method = "fudgeSpawnLocation",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/util/RandomSource;create()Lnet/minecraft/util/RandomSource;"))
    private RandomSource numen$seededSpawnSpread() {
        return RandomSource.create(BatchStart.entitySeed(((ServerPlayer) (Object) this).getId()));
    }
}
