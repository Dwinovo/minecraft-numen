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

import java.util.function.Consumer;

/**
 * 把一个 {@code static void xxx(GameTestHelper)} 方法(或现算出来的用例体)包成测试实例。
 *
 * <p>不用原版的 {@code FunctionGameTestInstance}:它按 {@code Registries.TEST_FUNCTION} 里的键取用例体,而那个注册表在
 * {@code BuiltInRegistries} 引导时就冻结了,模组加载轮不上。所以这里自带一个实例类型,直接持有用例体。
 *
 * <p>{@code TEST_INSTANCE} 是会同步给客户端的注册表,所以类型编解码器不能省:用例的键入档,解码时回
 * {@link NumenGameTests} 的名册里取回同一个用例体,与原版按键取函数是同一套路,只是名册在我们自己手里。
 */
public final class NumenTestInstance extends GameTestInstance {

    public static final MapCodec<NumenTestInstance> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Codec.STRING.fieldOf("case").forGetter(t -> t.key),
                    TestData.CODEC.forGetter(NumenTestInstance::info))
                    .apply(i, NumenTestInstance::new));

    private final String key;
    private final Consumer<GameTestHelper> body;

    public NumenTestInstance(String key, TestData<Holder<TestEnvironmentDefinition>> info) {
        super(info);
        this.key = key;
        this.body = NumenGameTests.body(key);
    }

    @Override
    public void run(GameTestHelper helper) {
        this.body.accept(helper);
    }

    @Override
    public MapCodec<NumenTestInstance> codec() {
        return CODEC;
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("numen function");
    }

    @Override
    public Component describe() {
        return this.describeType()
                .append(this.descriptionRow("test_instance.description.function", this.key))
                .append(this.describeInfo());
    }
}
