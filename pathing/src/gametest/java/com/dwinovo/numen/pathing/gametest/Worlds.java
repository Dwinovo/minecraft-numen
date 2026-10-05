package com.dwinovo.numen.pathing.gametest;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.gamerules.GameRules;

/** 寻路用例的批次开场:和平、正午、晴天、不自然刷怪——每一批都自己定,不继承上一批。 */
public final class Worlds {

    private Worlds() {}

    static void settle(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        // 26.1 把"当前时刻"从世界存档搬进了世界时钟:维度类型自带一口默认时钟,定时刻就是把这口钟的总刻数拨到目标值
        level.dimensionTypeRegistration().value().defaultClock()
                .ifPresent(clock -> level.clockManager().setTotalTicks(clock, 6000));
        level.getGameRules().set(GameRules.SPAWN_MOBS, false, level.getServer());
        pin(level);
        level.getServer().setWeatherParameters(24000, 0, false, false);
    }

    /**
     * 批次开场把场地钉住:方块不在用例手外自己变(随机刻让草皮枯成泥土、耕地失水;火在有玩家的区域里按刻变老、蔓延,身体是
     * 玩家),用例对账看的正是"除了她动过的,一格都没变"。
     */
    public static void pin(ServerLevel level) {
        level.getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, level.getServer());
        level.getGameRules().set(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0, level.getServer());
    }
}
