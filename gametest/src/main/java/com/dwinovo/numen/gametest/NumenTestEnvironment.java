package com.dwinovo.numen.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;

/**
 * 一个批次的环境:{@link TestEnvironmentDefinition#setup} 在批次开跑前调用一次、{@code teardown} 在收场后调用,于是批次的准备与善后
 * ({@link NumenBeforeBatch}、{@link NumenAfterBatch} 挂着的方法)就在这里做。批次没有对应方法时什么都不做。
 *
 * <p>环境条目只记批次的键,准备方法由 {@link NumenGameTests} 的名册按键给出(与测试实例同一个套路)。
 */
public record NumenTestEnvironment(String batch) implements TestEnvironmentDefinition {

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.STRING.fieldOf("batch").forGetter(NumenTestEnvironment::batch))
                    .apply(i, NumenTestEnvironment::new));

    @Override
    public void setup(ServerLevel level) {
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
