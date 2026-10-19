package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/** 寻路用例的批次开场:和平、正午、晴天、不自然刷怪——每一批都自己定,不继承上一批。 */
final class Worlds {

    private Worlds() {}

    static void settle(ServerLevel level) {
        GameTestWorld.INSTANCE.setDifficulty(level, Difficulty.PEACEFUL);
        level.setDayTime(6000);
        GameTestWorld.INSTANCE.setRule(level, level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING), false);
        level.setWeatherParameters(24000, 0, false, false);
    }
}
