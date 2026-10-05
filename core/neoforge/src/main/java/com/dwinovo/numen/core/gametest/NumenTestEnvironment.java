package com.dwinovo.numen.core.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Unit;

/**
 * 一个批次的开场与收尾。1.21.5 把"批次前置"这件事收进了测试环境:{@link TestEnvironmentDefinition#setup}
 * 在每个批次开跑前调用一次、{@code teardown} 在收场后调一次,于是旧代的 {@code @BeforeBatch} / {@code @AfterBatch}
 * 方法原样搬进来——环境只记批次名,方法由 {@link NumenGameTests} 的名册按名取回。
 *
 * <p>26.1 的 setup 返回一份"存档"、teardown 拿回来还原。批次开场定下的是整轮要压住的环境随机性(和平、正午、
 * 不自然刷怪……),批间还原会把上一批留在世界里的东西放回随机性里,所以存档类型取 {@link Unit},收尾只做批次自己声明的事。
 */
public record NumenTestEnvironment(String batch) implements TestEnvironmentDefinition<Unit> {

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.STRING.fieldOf("batch").forGetter(NumenTestEnvironment::batch))
                    .apply(i, NumenTestEnvironment::new));

    @Override
    public Unit setup(ServerLevel level) {
        NumenGameTests.beforeBatch(batch, level);
        return Unit.INSTANCE;
    }

    @Override
    public void teardown(ServerLevel level, Unit saveData) {
        NumenGameTests.afterBatch(batch, level);
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
