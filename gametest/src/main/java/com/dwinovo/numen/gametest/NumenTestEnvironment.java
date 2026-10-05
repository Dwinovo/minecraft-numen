package com.dwinovo.numen.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * 一个批次的环境:{@link TestEnvironmentDefinition#setup} 在批次开跑前调用一次、{@code teardown} 在收场后调用,于是批次的准备与善后
 * ({@link NumenBeforeBatch}、{@link NumenAfterBatch} 挂着的方法)就在这里做。批次没有对应方法时什么都不做。
 *
 * <p>环境条目只记批次的键,准备方法由 {@link NumenGameTests} 的名册按键给出(与测试实例同一个套路)。
 *
 * <p>批次方法之前先定下会在判据背后改世界的两条规则:随机刻停摆、火不蔓延也不老化(耕地湿度、草退化、火的年龄)。
 * 这是 1.21.5 之前 GameTestServer 自带规则里的两条,数据驱动之后原版的默认环境是空的,不补上的话用例要的"账上记的就是
 * 世界变的"就成立不了。难度、时刻、天气、刷怪由各批次的方法自己定(它们每批都要按用例的需要重定)。
 */
public record NumenTestEnvironment(String batch) implements TestEnvironmentDefinition {

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.STRING.fieldOf("batch").forGetter(NumenTestEnvironment::batch))
                    .apply(i, NumenTestEnvironment::new));

    @Override
    public void setup(ServerLevel level) {
        GameRules rules = level.getGameRules();
        rules.getRule(GameRules.RULE_RANDOMTICKING).set(0, level.getServer());
        rules.getRule(GameRules.RULE_DOFIRETICK).set(false, level.getServer());
        NumenGameTests.prepare(batch, level);
    }

    @Override
    public void teardown(ServerLevel level) {
        NumenGameTests.cleanup(batch, level);
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
