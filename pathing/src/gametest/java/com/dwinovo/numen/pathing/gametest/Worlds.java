package com.dwinovo.numen.pathing.gametest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/** 寻路用例的批次开场:和平、正午、晴天、不自然刷怪、火不自己长——每一批都自己定,不继承上一批。 */
final class Worlds {

    private Worlds() {}

    static void settle(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        // 摆在路边的火不能在走的途中自己变:账与世界对不上的用例靠它。1.21 的原版测试服务器自己关了火刻,本代的没有关
        level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(false, level.getServer());
        level.setWeatherParameters(24000, 0, false, false);
    }
}
