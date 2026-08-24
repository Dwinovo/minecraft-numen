package com.dwinovo.numen.core.gametest;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.Difficulty;

/**
 * 批次前置:和平难度 + 指定时刻。等价于旧代那七个 {@code @BeforeBatch} 方法
 * (它们的方法体逐字相同:{@code setDifficulty(PEACEFUL, true)} + {@code setDayTime(6000)}),
 * 1.21.5 把"批次前置"这件事收进了测试环境({@link TestEnvironmentDefinition#setup} 在
 * 每个批次开跑前调用一次),于是原样搬进来。
 *
 * <p>排除的是同两样东西:怪物袭扰(和平)与昼夜随机性(正午)。
 */
public record NumenTestEnvironment(int time) implements TestEnvironmentDefinition {

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(ExtraCodecs.NON_NEGATIVE_INT.fieldOf("time")
                    .forGetter(NumenTestEnvironment::time)).apply(i, NumenTestEnvironment::new));

    @Override
    public void setup(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(this.time);
        // 随机刻停摆。判据全是"世界最终长这样",而随机刻会在判据背后改世界:
        // 日式小屋图纸里有 163 格草方块,盖上屋顶后它们随机刻退化成泥土——
        // 而验收要的是「所有格<b>同时</b>就位」的那一瞬,先落的草在最后一格落定前
        // 就已经退化,那一瞬永远不会到来(实测 5857 格里稳定差这 163 格)。
        // 这和上面两条同类:排除与被测行为无关的环境随机性,不改任何被测逻辑。
        // 1.21.11 把游戏规则挪进 net.minecraft.world.level.gamerules 并改成
        // GameRule<T> + map 形态:取规则对象再 set(值, server),语义与旧代一致。
        level.getGameRules().set(
                net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 0, level.getServer());
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
