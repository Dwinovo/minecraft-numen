package com.dwinovo.numen.bench;

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
 * 一组场景的 GameTest 用例:跑 {@link Session}。
 *
 * <p>用例体不能进注册表数据(那是同步给客户端的),所以只记组名,解码时回 {@link NumenBench} 的名册取回会话——
 * 与核心的 {@code NumenTestInstance} 同一个做法。
 */
final class BenchTestInstance extends GameTestInstance {

    static final MapCodec<BenchTestInstance> CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(
                    Codec.STRING.fieldOf("suite").forGetter(t -> t.suite),
                    TestData.CODEC.forGetter(BenchTestInstance::info))
                    .apply(i, BenchTestInstance::new));

    private final String suite;
    private final Session session;

    BenchTestInstance(String suite, TestData<Holder<TestEnvironmentDefinition<?>>> info) {
        super(info);
        this.suite = suite;
        this.session = NumenBench.session(suite);
    }

    @Override
    public void run(GameTestHelper helper) {
        session.run(helper);
    }

    @Override
    public MapCodec<BenchTestInstance> codec() {
        return CODEC;
    }

    @Override
    protected MutableComponent typeDescription() {
        return Component.literal("numen bench suite");
    }

    @Override
    public Component describe() {
        return this.describeType()
                .append(this.descriptionRow("test_instance.description.function", this.suite))
                .append(this.describeInfo());
    }
}
