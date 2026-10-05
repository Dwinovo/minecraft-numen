package com.dwinovo.numen.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/**
 * 一条用例在 {@code minecraft:test_instance} 注册表里的样子。不用原版的 {@code FunctionGameTestInstance}:它按
 * {@code Registries.TEST_FUNCTION} 里的键取用例体,而那个注册表在引导时就冻结了,模组加载轮不上。这里按用例名到
 * {@link GameTests} 的名册里取用例体,与原版按键取函数是同一个套路,名册在我们自己手里。
 */
final class NumenTestInstance extends GameTestInstance {

    static final MapCodec<NumenTestInstance> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Codec.STRING.fieldOf("test").forGetter(t -> t.name),
                    TestData.CODEC.forGetter(NumenTestInstance::info))
                    .apply(i, NumenTestInstance::new));

    private final String name;

    NumenTestInstance(String name, TestData<Holder<TestEnvironmentDefinition>> info) {
        super(info);
        this.name = name;
    }

    @Override
    public void run(GameTestHelper helper) {
        GameTests.body(this.name).accept(helper);
    }

    @Override
    public MapCodec<NumenTestInstance> codec() {
        return CODEC;
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("numen test");
    }

    @Override
    public Component describe() {
        return this.describeType()
                .append(this.descriptionRow("test_instance.description.function", this.name))
                .append(this.describeInfo());
    }
}
