package com.dwinovo.numen.api.gametest.mixin.inject;

import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 原版 GameTest 服务器的固定游戏规则补上火刻:1.21 起原版自己把 {@code doFireTick} 关了,本代只关了刷怪、天气与随机刻。
 * 摆在场地里的火不能在用例途中自己长或灭——寻路"账与世界对得上"的用例、Paper 的测试服(它照 1.21 的那一套自己设规则)都靠它。
 */
@Mixin(GameTestServer.class)
public abstract class GameTestServerRulesMixin {

    @Shadow
    @Final
    private static GameRules TEST_GAME_RULES;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void numen$noFireTick(CallbackInfo ci) {
        TEST_GAME_RULES.getRule(GameRules.RULE_DOFIRETICK).set(false, null);
    }
}
