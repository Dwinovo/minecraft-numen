package com.dwinovo.numen.api.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 一批用例的测试环境:原版按环境分批,{@code setup} 在这一批开跑前调一次、{@code teardown} 在收场后调一次,批前批后的方法
 * ({@link BeforeBatch}、{@link AfterBatch})就在这里做。
 *
 * <p>批前方法之前先定下原版测试服原来自带的世界规则(1.21.5 之前 {@code GameTestServer} 建世界时就定好,数据驱动之后原版的默认环境是空的):
 * 不刷怪、不换天气、随机刻停摆、火不蔓延也不老化,天气晴。各批次的批前方法照旧按用例的需要再改。
 */
public record NumenTestEnvironment(String batch) implements TestEnvironmentDefinition {

    /** 环境类型在 {@code TEST_ENVIRONMENT_DEFINITION_TYPE} 注册表里的名字。 */
    public static final Identifier TYPE = Identifier.fromNamespaceAndPath("numen_api_gametest", "batch");

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.fieldOf("batch").forGetter(NumenTestEnvironment::batch))
            .apply(i, NumenTestEnvironment::new));

    /** 原版测试服原来给的晴天时长(刻)。 */
    private static final int CLEAR_WEATHER_TICKS = 20_000_000;

    @Override
    public void setup(ServerLevel level) {
        GameTestWorld world = GameTestWorld.INSTANCE;
        world.setRule(level, GameRules.SPAWN_MOBS, false);
        world.setRule(level, GameRules.ADVANCE_WEATHER, false);
        world.setRule(level, GameRules.RANDOM_TICK_SPEED, 0);
        // 火不蔓延、不老化:谁身边都不算火的活动范围
        world.setRule(level, GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        level.setWeatherParameters(CLEAR_WEATHER_TICKS, CLEAR_WEATHER_TICKS, false, false);
        GameTestSuites.beforeBatch(batch, level);
    }

    @Override
    public void teardown(ServerLevel level) {
        GameTestSuites.afterBatch(batch, level);
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
