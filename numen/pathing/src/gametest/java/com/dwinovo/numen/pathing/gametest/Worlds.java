package com.dwinovo.numen.pathing.gametest;

import com.dwinovo.numen.api.gametest.GameTestWorld;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;

/**
 * 寻路用例的批次开场:和平、正午、晴天、不自然刷怪、不随机刻也不烧火——每一批都自己定,不继承上一批。用例断言"世界在走的途中
 * 一格都没变":耕地会自己变干、火会自己变老,随机刻与火刻开着,这些格就会在账外悄悄变。
 */
final class Worlds {

    private Worlds() {}

    static void settle(ServerLevel level) {
        GameTestWorld.setDifficulty(level, Difficulty.PEACEFUL);
        level.setDayTime(6000);
        GameTestWorld.setRule(level, level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING), false);
        level.setWeatherParameters(24000, 0, false, false);
    }
}
