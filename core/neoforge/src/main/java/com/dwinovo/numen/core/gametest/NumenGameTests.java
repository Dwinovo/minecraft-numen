package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 游戏内用例的登记处。
 *
 * <h2>为什么有这一层</h2>
 * 1.21.5 把注解驱动的 gametest 整套拆了:{@code @GameTest} / {@code @BeforeBatch} /
 * {@code @GameTestHolder} 全部删除,用例变成 {@code minecraft:test_instance} 注册表里的
 * 数据条目(结构、超时、环境都进 {@code TestData}),批次前置变成"测试环境"
 * ({@code TestEnvironmentDefinition})。用例<b>本身</b>一行没变,变的只是登记方式。
 *
 * <p>于是这里把旧代注解的语义原样搭回去:反射扫 {@link CompanionGameTests} 上的
 * {@link NumenTest},每个方法登记成一条 {@link NumenTestInstance};每个 {@code batch}
 * 名登记成一个环境条目,把旧代 {@code @BeforeBatch} 的"和平 + 正午"搬进去。
 * 同批次的用例仍分在同一批里跑(1.21.5 按环境 Holder 分批),批次间的隔离
 * ——重型建造与小屋各自独占一批,不抢搜索池——因此保持不变。
 */
public final class NumenGameTests {

    private NumenGameTests() {}

    /** 旧代 {@code @BeforeBatch} 一律设正午;没有前置的批次走原版默认(空)环境。 */
    private static final int NOON = 6000;

    /**
     * 旧代<b>没有</b> {@code @BeforeBatch} 的批次——它们当年就不设难度和时刻,
     * 照搬这个"不做任何事"的语义,挂到原版默认环境上。
     */
    private static final List<String> NO_SETUP_BATCHES = List.of(
            "numen_combat", "numen_interact", "numen_inventory", "numen_smoke",
            "numen_survival", "numen_terrain", "numen_vehicle");

    /** 方法名 → 用例体。{@link NumenTestInstance} 解码时按名取回同一个方法。 */
    private static final Map<String, Consumer<GameTestHelper>> BODIES = new HashMap<>();

    public static void register(IEventBus modBus) {
        // 结构模板目录:运行配置经 numen.gametest.structures 指路。
        //
        // 必须在这里显式设,不能像旧代那样挂在 CompanionGameTests 的静态块里——本类只用
        // 反射读它的方法表,而 Class.getDeclaredMethods() <b>不触发静态初始化</b>,静态块
        // 一次都不会跑。漏掉的后果是模板找不到,用例以 "Failed to place test structure" 全灭。
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            // 1.21.5:testStructuresDir 由 String 改成 Path
            StructureUtils.testStructuresDir = java.nio.file.Paths.get(dir);
        }

        // 自带的实例类型 / 环境类型编解码器。两个都是 BuiltInRegistries 里的简单注册表,
        // 走 DeferredRegister 正常上;不注册的话 test_instance 同步给客户端时会找不到类型。
        DeferredRegister<MapCodec<? extends GameTestInstance>> instanceTypes =
                DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, Constants.MOD_ID);
        instanceTypes.register("function", () -> NumenTestInstance.CODEC);
        instanceTypes.register(modBus);

        DeferredRegister<MapCodec<? extends TestEnvironmentDefinition>> envTypes =
                DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Constants.MOD_ID);
        envTypes.register("batch", () -> NumenTestEnvironment.CODEC);
        envTypes.register(modBus);

        modBus.addListener(NumenGameTests::onRegisterGameTests);
    }

    /** 解码回调:按方法名取回用例体。名册在类加载时就由反射填好。 */
    static Consumer<GameTestHelper> body(String method) {
        Consumer<GameTestHelper> found = BODIES.get(method);
        if (found == null) {
            throw new IllegalStateException("unknown numen gametest: " + method);
        }
        return found;
    }

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        // 批次名 → 环境。用 LinkedHashMap 保住声明顺序,批次跑的先后与旧代一致。
        Map<String, Holder<TestEnvironmentDefinition>> environments = new LinkedHashMap<>();
        List<Method> tests = new ArrayList<>();

        for (Method m : CompanionGameTests.class.getDeclaredMethods()) {
            NumenTest spec = m.getAnnotation(NumenTest.class);
            if (spec == null) {
                continue;
            }
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1
                    || m.getParameterTypes()[0] != GameTestHelper.class) {
                throw new IllegalStateException(
                        "@NumenTest must sit on static void m(GameTestHelper): " + m);
            }
            tests.add(m);
        }
        // 反射拿到的方法顺序不保证稳定,按名字排一次,批次内的登记顺序才是可复现的。
        tests.sort(java.util.Comparator.comparing(Method::getName));

        for (Method m : tests) {
            NumenTest spec = m.getAnnotation(NumenTest.class);
            Holder<TestEnvironmentDefinition> env = environments.computeIfAbsent(spec.batch(),
                    batch -> environmentFor(event, batch));

            String name = m.getName();
            m.setAccessible(true);
            BODIES.put(name, helper -> {
                try {
                    m.invoke(null, helper);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    // 用例内抛出的断言/失败必须原样冒出去,不能被反射包一层
                    // ——包住就成了 InvocationTargetException,报告里看不出真正的失败原因。
                    Throwable cause = e.getCause();
                    if (cause instanceof RuntimeException re) {
                        throw re;
                    }
                    if (cause instanceof Error err) {
                        throw err;
                    }
                    throw new RuntimeException(cause);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("cannot invoke gametest " + name, e);
                }
            });

            TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(
                    env,
                    Identifier.fromNamespaceAndPath(Constants.MOD_ID, spec.template()),
                    spec.timeoutTicks(),
                    0,                  // setupTicks:旧代没有这一档
                    true,               // required:旧代 @GameTest 的默认值
                    Rotation.NONE);
            event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
                    new NumenTestInstance(name, data));
        }
        Constants.LOG.info("[numen-gametest] registered {} tests in {} batches",
                tests.size(), environments.size());
    }

    private static Holder<TestEnvironmentDefinition> environmentFor(RegisterGameTestsEvent event,
                                                                   String batch) {
        Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, batch);
        if (NO_SETUP_BATCHES.contains(batch)) {
            // 空环境:与旧代"这个批次没有 @BeforeBatch"逐字等价。
            return event.registerEnvironment(id, new TestEnvironmentDefinition.AllOf(List.of()));
        }
        return event.registerEnvironment(id, new NumenTestEnvironment(NOON));
    }
}
