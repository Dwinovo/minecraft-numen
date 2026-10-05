package com.dwinovo.numen.gametest;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforgespi.language.ModFileScanData;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.ElementType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 游戏内用例的登记处。
 *
 * <h2>为什么有这一层</h2>
 * 1.21.5 把注解驱动的 gametest 整套拆了:{@code @GameTest} / {@code @BeforeBatch} / {@code @GameTestHolder} 全部删除,用例变成
 * {@code minecraft:test_instance} 注册表里的数据条目(结构、超时、环境都进 {@code TestData}),批次前置变成"测试环境"
 * ({@code TestEnvironmentDefinition})。用例<b>本身</b>一行没变,变的只是登记方式:这里照旧代注解的语义原样搭回去。
 *
 * <h2>登记什么</h2>
 * 从模组的扫描数据里找所有挂着 {@link NumenGameTestHolder} 的类(core 的、寻路模块的、评测的,同一个模组 numen 的源码集),
 * 只收命名空间在运行配置 {@code neoforge.enabledGameTestNamespaces} 里的(没给或全是空白 = 全开;NeoForge 1.21.5 起不再认这个属性,
 * 这里按它原来的口径过滤)。每个类先初始化——用例类的静态块里登记夹具,得赶在结构模板加载之前——再读它的方法:
 * <ul>
 *   <li>{@link NumenTest} → 一条测试实例,编号 {@code <命名空间>:<类名>.<方法名>}(小写);</li>
 *   <li>{@link NumenBeforeBatch} / {@link NumenAfterBatch} → 同名批次环境的准备与善后;</li>
 *   <li>{@link NumenGameTestGenerator} → 现算出的 {@link NumenTestCase} 一批。</li>
 * </ul>
 * 同一个批次名登记成一个环境条目,同批次的用例仍分在同一批里跑(1.21.5 按环境分批),批次间的隔离因此保持不变。
 */
@EventBusSubscriber(modid = NumenGameTests.MOD_ID)
public final class NumenGameTests {

    /** 登记类随开发运行配置里的这个模组加载。 */
    static final String MOD_ID = "numen";

    private static final Logger LOG = LoggerFactory.getLogger("numen-gametest");

    /** 用例键 → 用例体。{@link NumenTestInstance} 解码时按键取回同一个。 */
    private static final Map<String, Consumer<GameTestHelper>> BODIES = new HashMap<>();
    /** 批次键 → 这个批次开跑前要做的准备,按登记顺序。 */
    private static final Map<String, List<Method>> PREPARE = new HashMap<>();
    /** 批次键 → 这个批次收场后要做的善后。 */
    private static final Map<String, List<Method>> CLEANUP = new HashMap<>();

    private NumenGameTests() {}

    @SubscribeEvent
    static void registerTypes(RegisterEvent event) {
        // 自带的实例类型 / 环境类型编解码器。不注册的话 test_instance 同步给客户端时找不到类型。
        event.register(Registries.TEST_INSTANCE_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "function"),
                () -> NumenTestInstance.CODEC);
        event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Identifier.fromNamespaceAndPath(MOD_ID, "batch"),
                () -> NumenTestEnvironment.CODEC);
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) throws ReflectiveOperationException {
        // 结构模板目录:运行配置经 numen.gametest.structures 指路(寻路模块与 core 的模板同步进同一处,见 core/neoforge/build.gradle)
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            StructureUtils.testStructuresDir = Paths.get(dir);
        }

        // 类名 → 命名空间,按类名排序:登记顺序是可复现的
        Map<String, String> holders = new TreeMap<>();
        for (ModFileScanData scan : ModList.get().getAllScanData()) {
            scan.getAnnotatedBy(NumenGameTestHolder.class, ElementType.TYPE).forEach(found ->
                    holders.put(found.clazz().getClassName(), (String) found.annotationData().get("value")));
        }
        Set<String> enabled = enabledNamespaces();

        Map<String, Holder<TestEnvironmentDefinition>> environments = new LinkedHashMap<>();
        int tests = 0;
        for (Map.Entry<String, String> holder : holders.entrySet()) {
            String namespace = holder.getValue();
            if (!enabled.isEmpty() && !enabled.contains(namespace)) {
                continue;
            }
            Class<?> type = Class.forName(holder.getKey(), true, NumenGameTests.class.getClassLoader());
            Method[] methods = type.getDeclaredMethods();
            // 反射拿到的方法顺序不保证稳定,按名字排一次,批次内的登记顺序才是可复现的
            Arrays.sort(methods, java.util.Comparator.comparing(Method::getName));

            for (Method m : methods) {
                NumenBeforeBatch before = m.getAnnotation(NumenBeforeBatch.class);
                if (before != null) {
                    requireStatic(m, ServerLevel.class);
                    m.setAccessible(true);
                    PREPARE.computeIfAbsent(batchKey(namespace, before.batch()), k -> new ArrayList<>()).add(m);
                }
                NumenAfterBatch after = m.getAnnotation(NumenAfterBatch.class);
                if (after != null) {
                    requireStatic(m, ServerLevel.class);
                    m.setAccessible(true);
                    CLEANUP.computeIfAbsent(batchKey(namespace, after.batch()), k -> new ArrayList<>()).add(m);
                }
            }
            for (Method m : methods) {
                NumenTest spec = m.getAnnotation(NumenTest.class);
                if (spec != null) {
                    requireStatic(m, GameTestHelper.class);
                    m.setAccessible(true);
                    String name = type.getSimpleName().toLowerCase(Locale.ROOT) + "." + m.getName().toLowerCase(Locale.ROOT);
                    register(event, environments, namespace, new NumenTestCase(name, spec.template(), spec.timeoutTicks(),
                            spec.batch(), invoker(m)));
                    tests++;
                }
                if (m.isAnnotationPresent(NumenGameTestGenerator.class)) {
                    if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0
                            || !Collection.class.isAssignableFrom(m.getReturnType())) {
                        throw new IllegalStateException("@NumenGameTestGenerator must sit on static Collection<NumenTestCase> m(): " + m);
                    }
                    m.setAccessible(true);
                    @SuppressWarnings("unchecked")
                    Collection<NumenTestCase> generated = (Collection<NumenTestCase>) invoke(m);
                    for (NumenTestCase generatedCase : generated) {
                        register(event, environments, namespace, generatedCase);
                        tests++;
                    }
                }
            }
        }
        LOG.info("[numen-gametest] registered {} tests in {} batches", tests, environments.size());
    }

    /** 用例体:登记时取得;{@link NumenTestInstance} 的构造与解码都回这里要。 */
    static Consumer<GameTestHelper> body(String key) {
        Consumer<GameTestHelper> found = BODIES.get(key);
        if (found == null) {
            throw new IllegalStateException("unknown numen gametest: " + key);
        }
        return found;
    }

    /** 批次开跑前的准备;这个批次没有准备方法就什么都不做。 */
    static void prepare(String batchKey, ServerLevel level) {
        for (Method m : PREPARE.getOrDefault(batchKey, List.of())) {
            invoke(m, level);
        }
    }

    /** 批次收场后的善后;这个批次没有善后方法就什么都不做。 */
    static void cleanup(String batchKey, ServerLevel level) {
        for (Method m : CLEANUP.getOrDefault(batchKey, List.of())) {
            invoke(m, level);
        }
    }

    private static void register(RegisterGameTestsEvent event, Map<String, Holder<TestEnvironmentDefinition>> environments,
                                 String namespace, NumenTestCase test) {
        String batch = batchKey(namespace, test.batch());
        Holder<TestEnvironmentDefinition> environment = environments.computeIfAbsent(batch, key ->
                event.registerEnvironment(Identifier.fromNamespaceAndPath(namespace, test.batch().toLowerCase(Locale.ROOT)),
                        new NumenTestEnvironment(key)));
        String key = namespace + ":" + test.name().toLowerCase(Locale.ROOT);
        BODIES.put(key, test.body());
        TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(
                environment,
                Identifier.fromNamespaceAndPath(namespace, test.template()),
                test.timeoutTicks(),
                0,                  // setupTicks:旧代没有这一档
                true,               // required:旧代 @GameTest 的默认值
                Rotation.NONE);
        event.registerTest(Identifier.fromNamespaceAndPath(namespace, test.name().toLowerCase(Locale.ROOT)),
                new NumenTestInstance(key, data));
    }

    private static String batchKey(String namespace, String batch) {
        return namespace + ":" + batch.toLowerCase(Locale.ROOT);
    }

    /** 运行配置开了哪些命名空间;空 = 全开。 */
    private static Set<String> enabledNamespaces() {
        String property = System.getProperty("neoforge.enabledGameTestNamespaces");
        if (property == null) {
            return Set.of();
        }
        return Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    private static void requireStatic(Method m, Class<?> parameter) {
        if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 1 || m.getParameterTypes()[0] != parameter) {
            throw new IllegalStateException("must sit on static void m(" + parameter.getSimpleName() + "): " + m);
        }
    }

    private static Consumer<GameTestHelper> invoker(Method m) {
        return helper -> invoke(m, helper);
    }

    /** 反射调用;方法里抛出的断言/失败原样冒出去,不能被反射包一层——包住就看不出真正的失败原因。 */
    private static Object invoke(Method m, Object... args) {
        try {
            return m.invoke(null, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("cannot invoke gametest method " + m, e);
        }
    }
}
