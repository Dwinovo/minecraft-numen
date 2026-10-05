package com.dwinovo.numen.gametest;

import com.mojang.logging.LogUtils;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import net.neoforged.neoforgespi.language.ModFileScanData;

import org.slf4j.Logger;

import java.lang.annotation.ElementType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * 游戏内用例的登记处。
 *
 * <p>1.21.5 起用例是 {@code minecraft:test_instance} 注册表里的数据条目,批次前后的准备是"测试环境"
 * ({@link TestEnvironmentDefinition}),原版不再有按注解登记的那一套。这里把按注解写用例的写法接了回来:
 * 扫描所有挂着 {@link NumenTestHolder} 的类,{@link NumenTest} 与 {@link NumenTestGenerator} 登记成用例,
 * 每个批次登记成一个环境,{@link BeforeBatch} 与 {@link AfterBatch} 是这个环境的开场与收场。
 * 同一批次的用例原版仍分在同一批里跑。
 *
 * <p>只加载运行配置开了命名空间的类({@code neoforge.enabledGameTestNamespaces},没给或全是空白 = 全开):
 * 类的静态块会登记只给用例用的夹具,别的命名空间的运行里不该有。
 */
@EventBusSubscriber(modid = GameTests.MOD_ID)
public final class GameTests {

    /** 登记的类型(用例类型、环境类型)挂在本模组名下。 */
    static final String MOD_ID = "numen";

    /** 结构模板目录,运行配置经这个属性指过来。 */
    private static final String STRUCTURES = "numen.gametest.structures";

    /** 用例全名 → 用例体。{@link NumenTestInstance} 按名取回。 */
    private static final Map<String, Consumer<GameTestHelper>> BODIES = new HashMap<>();
    /** 批次全名({@code 命名空间:批次名})→ 开场与收场的钩子。 */
    private static final Map<String, List<Consumer<ServerLevel>>> BEFORE = new HashMap<>();
    private static final Map<String, List<Consumer<ServerLevel>>> AFTER = new HashMap<>();

    private static final Logger LOG = LogUtils.getLogger();

    private GameTests() {}

    static Consumer<GameTestHelper> body(String name) {
        Consumer<GameTestHelper> found = BODIES.get(name);
        if (found == null) {
            throw new IllegalStateException("unknown numen gametest: " + name);
        }
        return found;
    }

    static List<Consumer<ServerLevel>> before(String batch) {
        return BEFORE.getOrDefault(batch, List.of());
    }

    static List<Consumer<ServerLevel>> after(String batch) {
        return AFTER.getOrDefault(batch, List.of());
    }

    @SubscribeEvent
    static void registerTypes(RegisterEvent event) {
        // test_instance 与 test_environment 的类型表会同步给客户端,没有类型编解码器就解不出我们的条目
        event.register(Registries.TEST_INSTANCE_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "test"),
                () -> NumenTestInstance.CODEC);
        event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, ResourceLocation.fromNamespaceAndPath(MOD_ID, "batch"),
                () -> NumenTestEnvironment.CODEC);
    }

    @SubscribeEvent
    static void registerTests(RegisterGameTestsEvent event) {
        String dir = System.getProperty(STRUCTURES);
        if (dir != null) {
            StructureUtils.testStructuresDir = Paths.get(dir);
        }
        List<String> enabled = Arrays.stream(System.getProperty("neoforge.enabledGameTestNamespaces", "").split(","))
                .filter(s -> !s.isBlank()).toList();

        Map<String, Holder<TestEnvironmentDefinition>> environments = new LinkedHashMap<>();
        TreeSet<String> classes = new TreeSet<>();
        for (ModFileScanData data : ModList.get().getAllScanData()) {
            data.getAnnotatedBy(NumenTestHolder.class, ElementType.TYPE)
                    .forEach(a -> classes.add(a.clazz().getClassName()));
        }
        int count = 0;
        for (String className : classes) {
            Class<?> holder = load(className, false);
            String namespace = holder.getAnnotation(NumenTestHolder.class).value();
            if (!enabled.isEmpty() && !enabled.contains(namespace)) {
                continue;
            }
            load(className, true);
            Method[] methods = holder.getDeclaredMethods();
            Arrays.sort(methods, Comparator.comparing(Method::getName));
            for (Method m : methods) {
                BeforeBatch before = m.getAnnotation(BeforeBatch.class);
                if (before != null) {
                    expectSignature(m, ServerLevel.class);
                    BEFORE.computeIfAbsent(namespace + ":" + before.batch(), k -> new ArrayList<>())
                            .add(level -> invoke(m, level));
                }
                AfterBatch after = m.getAnnotation(AfterBatch.class);
                if (after != null) {
                    expectSignature(m, ServerLevel.class);
                    AFTER.computeIfAbsent(namespace + ":" + after.batch(), k -> new ArrayList<>())
                            .add(level -> invoke(m, level));
                }
            }
            for (Method m : methods) {
                NumenTest spec = m.getAnnotation(NumenTest.class);
                if (spec != null) {
                    expectSignature(m, GameTestHelper.class);
                    String name = holder.getSimpleName().toLowerCase(Locale.ROOT) + "." + m.getName();
                    register(event, environments, namespace, new TestCase(name, spec.template(), spec.timeoutTicks(),
                            spec.batch(), helper -> invoke(m, helper)));
                    count++;
                }
                if (m.isAnnotationPresent(NumenTestGenerator.class)) {
                    expectSignature(m);
                    for (TestCase made : cases(m)) {
                        register(event, environments, namespace, made);
                        count++;
                    }
                }
            }
        }
        LOG.info("registered {} tests in {} batches", count, environments.size());
    }

    private static Class<?> load(String className, boolean initialize) {
        try {
            return Class.forName(className, initialize, GameTests.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("gametest class not found: " + className, e);
        }
    }

    private static void register(RegisterGameTestsEvent event, Map<String, Holder<TestEnvironmentDefinition>> environments,
                                 String namespace, TestCase test) {
        String batch = namespace + ":" + test.batch();
        Holder<TestEnvironmentDefinition> environment = environments.computeIfAbsent(batch,
                key -> event.registerEnvironment(ResourceLocation.fromNamespaceAndPath(namespace, test.batch()),
                        new NumenTestEnvironment(key)));
        String name = namespace + ":" + test.name();
        BODIES.put(name, test.body());
        TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(environment,
                ResourceLocation.fromNamespaceAndPath(namespace, test.template()),
                test.timeoutTicks(), 0, true, Rotation.NONE);
        event.registerTest(ResourceLocation.fromNamespaceAndPath(namespace, test.name()),
                new NumenTestInstance(name, data));
    }

    @SuppressWarnings("unchecked")
    private static Collection<TestCase> cases(Method generator) {
        try {
            return (Collection<TestCase>) generator.invoke(null);
        } catch (InvocationTargetException | IllegalAccessException e) {
            throw new IllegalStateException("gametest generator failed: " + generator, e);
        }
    }

    private static void expectSignature(Method m, Class<?>... parameters) {
        if (!Modifier.isStatic(m.getModifiers()) || !Arrays.equals(m.getParameterTypes(), parameters)) {
            throw new IllegalStateException("gametest annotation sits on a method with the wrong signature: " + m);
        }
    }

    /** 调用例或钩子;里面抛出的断言与失败原样冒出去,不被反射包一层,报告里才看得出真正的原因。 */
    private static void invoke(Method m, Object argument) {
        try {
            m.setAccessible(true);
            m.invoke(null, argument);
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
            throw new IllegalStateException("cannot invoke " + m, e);
        }
    }
}
