package com.dwinovo.numen.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;

/**
 * 一个批次的环境:开跑前执行这一批的 {@link BeforeBatch},跑完执行 {@link AfterBatch}。原版按环境分批,同一个环境的用例
 * 分在同一批里,所以一个批次名对应一个环境;钩子在 {@link GameTests} 的名册里,按 {@code batch}(命名空间加批次名)取。
 */
record NumenTestEnvironment(String batch) implements TestEnvironmentDefinition {

    static final MapCodec<NumenTestEnvironment> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.STRING.fieldOf("batch").forGetter(NumenTestEnvironment::batch))
                    .apply(i, NumenTestEnvironment::new));

    @Override
    public void setup(ServerLevel level) {
        // 随机刻与火刻停摆:用例的判据都是"世界最终长这样",而这两样会在判据背后改世界(耕地失水、草皮退化成泥土、
        // 火蔓延与变老),与被测的行为无关。1.21.4 以前的 GameTestServer 开服就把它们关了;1.21.5 起原版的默认
        // 环境是空的,这里补回。
        level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOFIRETICK).set(false, level.getServer());
        GameTests.before(this.batch).forEach(hook -> hook.accept(level));
    }

    @Override
    public void teardown(ServerLevel level) {
        GameTests.after(this.batch).forEach(hook -> hook.accept(level));
    }

    @Override
    public MapCodec<NumenTestEnvironment> codec() {
        return CODEC;
    }
}
