package com.dwinovo.numen.core.gametest;

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
 * 把一个 {@code static void xxx(GameTestHelper)} 方法包成 1.21.5 的测试实例。
 *
 * <p>不用原版的 {@code FunctionGameTestInstance}:它按 {@code Registries.TEST_FUNCTION}
 * 里的键取用例体,而那个注册表在 {@code BuiltInRegistries} 引导时就冻结了
 * ({@code BuiltinTestFunctions::bootstrap} 一次跑完所有 loader),模组加载轮不上。
 * 所以这里自带一个实例类型,直接持有方法引用。
 *
 * <p>{@code TEST_INSTANCE} 是会同步给客户端的注册表,所以类型编解码器不能省:
 * 用例名入档、解码时回 {@link NumenGameTests} 的名册里取回同一个方法体,
 * 与原版按键取函数是同一套路,只是名册在我们自己手里。
 */
public final class NumenTestInstance extends GameTestInstance {

    public static final MapCodec<NumenTestInstance> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Codec.STRING.fieldOf("method").forGetter(t -> t.method),
                    TestData.CODEC.forGetter(NumenTestInstance::info))
                    .apply(i, NumenTestInstance::new));

    private final String method;
    private final Consumer<GameTestHelper> body;

    public NumenTestInstance(String method, TestData<Holder<TestEnvironmentDefinition<?>>> info) {
        super(info);
        this.method = method;
        this.body = NumenGameTests.body(method);
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
                .append(this.descriptionRow("test_instance.description.function", this.method))
                .append(this.describeInfo());
    }
}
