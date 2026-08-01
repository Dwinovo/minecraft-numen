package com.dwinovo.numen.core.gametest;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.Unit;
import net.minecraft.world.Difficulty;

/**
 * 批次前置:和平难度 + 指定时刻。等价于旧代那七个 {@code @BeforeBatch} 方法
 * (它们的方法体逐字相同:{@code setDifficulty(PEACEFUL, true)} + 把时刻定在正午),
 * 1.21.5 把"批次前置"这件事收进了测试环境({@link TestEnvironmentDefinition#setup} 在
 * 每个批次开跑前调用一次),于是原样搬进来。
 *
 * <p>排除的是同两样东西:怪物袭扰(和平)与昼夜随机性(正午)。
 */
public record NumenTestEnvironment(int time) implements TestEnvironmentDefinition<Unit> {

    public static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(ExtraCodecs.NON_NEGATIVE_INT.fieldOf("time")
                    .forGetter(NumenTestEnvironment::time)).apply(i, NumenTestEnvironment::new));

    @Override
    public Unit setup(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        // 26.1 把"当前时刻"从世界存档搬进了世界时钟(WorldClock/ServerClockManager):
        // 维度类型自带一口默认时钟,定时刻就是把这口钟的总刻数拨到目标值。
        // 语义与旧代的 setDayTime(6000) 一致——正午,昼夜不再漂移。
        level.dimensionTypeRegistration().value().defaultClock().ifPresent(
                clock -> level.clockManager().setTotalTicks(clock, this.time));
        // 随机刻停摆。判据全是"世界最终长这样",而随机刻会在判据背后改世界:
        // 日式小屋图纸里有 163 格草方块,盖上屋顶后它们随机刻退化成泥土——
        // 而验收要的是「所有格<b>同时</b>就位」的那一瞬,先落的草在最后一格落定前
        // 就已经退化,那一瞬永远不会到来(实测 5857 格里稳定差这 163 格)。
        // 这和上面两条同类:排除与被测行为无关的环境随机性,不改任何被测逻辑。
        // 1.21.11 把游戏规则挪进 net.minecraft.world.level.gamerules 并改成
        // GameRule<T> + map 形态:取规则对象再 set(值, server),语义与旧代一致。
        level.getGameRules().set(
                net.minecraft.world.level.gamerules.GameRules.RANDOM_TICK_SPEED, 0, level.getServer());
        return Unit.INSTANCE;
    }

    /**
     * 刻意留空。26.1 给测试环境补了"批次收尾还原现场"的能力({@code setup} 返回
     * 存档、{@code teardown} 拿回来还原),但这三样前置的<b>本意就是整轮压住</b>
     * 环境随机性——尤其随机刻:批间还原成 3,上一批留在世界里的草方块会在下一批
     * 的 setup 重新压住之前退化成泥土,正是本类要防的那个坑。故不还原。
     */
    @Override
    public void teardown(ServerLevel level, Unit saveData) {
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
