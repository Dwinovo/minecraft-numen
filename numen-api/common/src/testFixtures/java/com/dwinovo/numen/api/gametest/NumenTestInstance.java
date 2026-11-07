package com.dwinovo.numen.api.gametest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.function.Consumer;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;

/**
 * 一条 {@link TestFunction} 作为原版的测试实例。不用原版的 {@code FunctionGameTestInstance}:它按 {@code TEST_FUNCTION} 注册表里的键取
 * 用例体,那个注册表在原版引导时就冻结了,模组轮不上。这里直接持有用例体;测试实例注册表会同步给客户端,所以类型要登记
 * ({@link #TYPE},各加载器的开发期小模组登记),编解码只带用例全名,解码时回 {@link GameTestSuites} 按名字取同一条。
 */
public final class NumenTestInstance extends GameTestInstance {

    /** 实例类型在 {@code TEST_INSTANCE_TYPE} 注册表里的名字。 */
    public static final Identifier TYPE = Identifier.fromNamespaceAndPath("numen_api_gametest", "function");

    public static final MapCodec<NumenTestInstance> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                    Codec.STRING.fieldOf("name").forGetter(NumenTestInstance::name),
                    TestData.CODEC.forGetter(NumenTestInstance::info))
            .apply(i, (name, info) -> new NumenTestInstance(GameTestRegistration.function(name), info)));

    private final TestFunction function;

    NumenTestInstance(TestFunction function, TestData<Holder<TestEnvironmentDefinition<?>>> info) {
        super(info);
        this.function = function;
    }

    /** 这条用例。 */
    public TestFunction function() {
        return function;
    }

    /** 用例全名({@link TestFunction#testName()})。 */
    public String name() {
        return function.testName();
    }

    /** 这条在跑的用例的全名;不是我们登记的用例(加载器或别的模组的)就是它的注册名。 */
    public static String name(GameTestInfo info) {
        return info.getTest() instanceof NumenTestInstance ours ? ours.name() : info.id().toString();
    }

    @Override
    public void run(GameTestHelper helper) {
        Consumer<GameTestHelper> body = function.function();
        body.accept(helper);
    }

    @Override
    public MapCodec<NumenTestInstance> codec() {
        return CODEC;
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("Numen test function");
    }

    @Override
    public Component describe() {
        return describeType().append(descriptionRow("test_instance.description.function", name())).append(describeInfo());
    }
}
