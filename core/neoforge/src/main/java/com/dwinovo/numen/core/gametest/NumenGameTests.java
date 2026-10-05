package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.pathing.gametest.NumenAfterBatch;
import com.dwinovo.numen.pathing.gametest.NumenBeforeBatch;
import com.dwinovo.numen.pathing.gametest.NumenTest;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforgespi.language.ModFileScanData;

import java.lang.annotation.ElementType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;

/**
 * 游戏内用例的登记处。
 *
 * <h2>为什么有这一层</h2>
 * 1.21.5 把注解驱动的 gametest 整套拆了:{@code @GameTest} / {@code @BeforeBatch} / {@code @AfterBatch} /
 * {@code @GameTestHolder} 全部删除,用例变成 {@code minecraft:test_instance} 注册表里的数据条目(结构、超时、环境都进
 * {@code TestData}),批次前置与收尾变成"测试环境"({@code TestEnvironmentDefinition})的 setup / teardown。
 * 用例<b>本身</b>一行没变,变的只是登记方式。
 *
 * <p>于是这里把旧代注解的语义原样搭回去:经 FML 的注解扫描找出本模组里所有 {@link NumenTest} /
 * {@link NumenBeforeBatch} / {@link NumenAfterBatch}(核心的与寻路模块的用例同在一个模组里,一次扫完),
 * 每个用例方法登记成一条 {@link NumenTestInstance},每个批次登记成一个 {@link NumenTestEnvironment}。
 * 同批次的用例仍分在同一批里跑(1.21.5 按环境 Holder 分批),批次间的隔离——重型建造与小屋各自独占一批,
 * 不抢搜索池——因此保持不变。
 *
 * <p>开发期专用:{@code NumenCoreNeoForge} 只在 GameTest 开着时才调 {@link #register},注解类住在寻路的 GameTest
 * 源码集里,发行 jar 里没有它们。
 */
public final class NumenGameTests {

    private NumenGameTests() {}

    /** 用例名 → 用例体。{@link NumenTestInstance} 解码时按名取回同一个方法。 */
    private static final Map<String, Consumer<GameTestHelper>> BODIES = new HashMap<>();
    /** 批次名 → 开场 / 收尾。{@link NumenTestEnvironment} 的 setup / teardown 按批次名取回。 */
    private static final Map<String, Consumer<ServerLevel>> BEFORE = new HashMap<>();
    private static final Map<String, Consumer<ServerLevel>> AFTER = new HashMap<>();

    public static void register(IEventBus modBus) {
        // 结构模板目录:运行配置经 numen.gametest.structures 指路。
        //
        // 必须在这里显式设:1.21.5 起 testStructuresDir 由 String 改成 Path,26.1 再拆成"读"(source,模板从这里
        // 加载)与"写"(target,导出落这里)两个字段。我们这一份目录两头都用(读 .snbt 模板 + 读图纸夹具),原样两个都指过去。
        String dir = System.getProperty("numen.gametest.structures");
        if (dir != null) {
            java.nio.file.Path structures = java.nio.file.Paths.get(dir);
            StructureUtils.testStructuresSourceDir = structures;
            StructureUtils.testStructuresTargetDir = structures;
        }

        // 自带的实例类型 / 环境类型编解码器。两个都是 BuiltInRegistries 里的简单注册表,
        // 走 DeferredRegister 正常上;不注册的话 test_instance 同步给客户端时会找不到类型。
        DeferredRegister<MapCodec<? extends GameTestInstance>> instanceTypes =
                DeferredRegister.create(Registries.TEST_INSTANCE_TYPE, Constants.MOD_ID);
        instanceTypes.register("function", () -> NumenTestInstance.CODEC);
        instanceTypes.register(modBus);

        DeferredRegister<MapCodec<? extends TestEnvironmentDefinition<?>>> envTypes =
                DeferredRegister.create(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, Constants.MOD_ID);
        envTypes.register("batch", () -> NumenTestEnvironment.CODEC);
        envTypes.register(modBus);

        modBus.addListener(NumenGameTests::onRegisterGameTests);
        if (GameTestKit.numenTestsEnabled()) {
            NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> pace());
        }
    }

    /** 一刻至少这么久(纳秒):2 毫秒,一秒最多 500 刻。 */
    private static final long MIN_TICK_NANOS = 2_000_000L;
    private static long lastTickNanos;

    /**
     * 补足每刻的最短时长。GameTest 服务器的一刻不等下一刻,而 26.1 的一刻只要几十微秒:用例的时限按刻写、搜索与诊断按墙钟算,
     * 几百刻的时限几十毫秒就到,搜索线程还没来得及交回结果。补到 2 毫秒一刻,时限与异步的活的比例就和 1.21.1 上实际的节奏
     * (一秒约 600 刻)相当。
     */
    private static void pace() {
        long wait = lastTickNanos + MIN_TICK_NANOS - System.nanoTime();
        if (wait > 0) {
            LockSupport.parkNanos(wait);
        }
        lastTickNanos = System.nanoTime();
    }

    /** 解码回调:按名取回用例体。 */
    static Consumer<GameTestHelper> body(String name) {
        Consumer<GameTestHelper> found = BODIES.get(name);
        if (found == null) {
            throw new IllegalStateException("unknown numen gametest: " + name);
        }
        return found;
    }

    static void beforeBatch(String batch, ServerLevel level) {
        Consumer<ServerLevel> hook = BEFORE.get(batch);
        if (hook != null) {
            hook.accept(level);
        }
    }

    static void afterBatch(String batch, ServerLevel level) {
        Consumer<ServerLevel> hook = AFTER.get(batch);
        if (hook != null) {
            hook.accept(level);
        }
    }

    /** 扫到的一个注解方法:它所在的类(已初始化)、方法、注解上写的值。 */
    private record Found(Method method, Map<String, Object> values) {}

    private static void onRegisterGameTests(RegisterGameTestsEvent event) {
        // numen 自己的用例这次没开(评测、联动的 GameTest 只开自己的命名空间):夹具也不登记。
        if (!GameTestKit.numenTestsEnabled()) {
            return;
        }
        ModFileScanData scan = ModList.get().getModFileById(Constants.MOD_ID).getFile().getScanResult();
        List<Found> tests = find(scan, NumenTest.class.getName());
        List<Found> befores = find(scan, NumenBeforeBatch.class.getName());
        List<Found> afters = find(scan, NumenAfterBatch.class.getName());

        for (Found f : befores) {
            hook(BEFORE, (String) f.values().get("batch"), f.method());
        }
        for (Found f : afters) {
            hook(AFTER, (String) f.values().get("batch"), f.method());
        }

        // 批次名 → 环境。用 LinkedHashMap 保住扫描排序后的顺序,批次跑的先后因此可复现。
        Map<String, Holder<TestEnvironmentDefinition<?>>> environments = new LinkedHashMap<>();
        for (Found f : tests) {
            Method m = f.method();
            String batch = (String) f.values().get("batch");
            Holder<TestEnvironmentDefinition<?>> env = environments.computeIfAbsent(batch,
                    b -> environmentFor(event, b));

            String name = nameOf(m);
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

            TestData<Holder<TestEnvironmentDefinition<?>>> data = new TestData<>(
                    env,
                    Identifier.fromNamespaceAndPath(Constants.MOD_ID, (String) f.values().get("template")),
                    (Integer) f.values().get("timeoutTicks"),
                    0,                  // setupTicks:旧代没有这一档
                    true,               // required:旧代 @GameTest 的默认值
                    Rotation.NONE);
            event.registerTest(Identifier.fromNamespaceAndPath(Constants.MOD_ID, name),
                    new NumenTestInstance(name, data));
        }
        Constants.LOG.info("[numen-gametest] registered {} tests in {} batches",
                tests.size(), environments.size());
    }

    /**
     * 本模组里挂着 {@code annotation} 的静态方法,按"类名.方法名"排序(扫描结果是无序集,排一次登记顺序才可复现)。
     * 找到的类当场初始化:用例类的静态块里登记着夹具(只给用例用的命令组与任务),要赶在用例跑起来之前。
     */
    private static List<Found> find(ModFileScanData scan, String annotation) {
        List<Found> out = new ArrayList<>();
        scan.getAnnotations().stream()
                .filter(a -> a.targetType() == ElementType.METHOD
                        && a.annotationType().getClassName().equals(annotation))
                .forEach(a -> {
                    String owner = a.clazz().getClassName();
                    String member = a.memberName();
                    String methodName = member.substring(0, member.indexOf('('));
                    try {
                        Class<?> holder = Class.forName(owner, true, NumenGameTests.class.getClassLoader());
                        for (Method m : holder.getDeclaredMethods()) {
                            if (m.getName().equals(methodName) && Modifier.isStatic(m.getModifiers())) {
                                out.add(new Found(m, a.annotationData()));
                                return;
                            }
                        }
                        throw new IllegalStateException("static method not found: " + owner + "." + member);
                    } catch (ClassNotFoundException e) {
                        throw new IllegalStateException("cannot load gametest class " + owner, e);
                    }
                });
        out.sort(Comparator.comparing(f -> nameOf(f.method())));
        return out;
    }

    private static void hook(Map<String, Consumer<ServerLevel>> into, String batch, Method m) {
        if (m.getParameterCount() != 1 || m.getParameterTypes()[0] != ServerLevel.class) {
            throw new IllegalStateException("a batch hook must be static void m(ServerLevel): " + m);
        }
        m.setAccessible(true);
        Consumer<ServerLevel> previous = into.put(batch, level -> {
            try {
                m.invoke(null, level);
            } catch (java.lang.reflect.InvocationTargetException e) {
                if (e.getCause() instanceof RuntimeException re) {
                    throw re;
                }
                throw new RuntimeException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot invoke batch hook " + m, e);
            }
        });
        if (previous != null) {
            throw new IllegalStateException("batch " + batch + " has two hooks of the same kind: " + m);
        }
    }

    /** 用例名 = 类名(小写).方法名:跨类同名的方法(两个类里都有的 walks_straight 之类)不撞车。 */
    private static String nameOf(Method m) {
        return m.getDeclaringClass().getSimpleName().toLowerCase(Locale.ROOT) + "." + m.getName();
    }

    private static Holder<TestEnvironmentDefinition<?>> environmentFor(RegisterGameTestsEvent event, String batch) {
        Identifier id = Identifier.fromNamespaceAndPath(Constants.MOD_ID, batch);
        if (!BEFORE.containsKey(batch) && !AFTER.containsKey(batch)) {
            // 没有开场与收尾的批次:空环境,与旧代"这个批次没有 @BeforeBatch"逐字等价。
            return event.registerEnvironment(id, new TestEnvironmentDefinition.AllOf(List.of()));
        }
        return event.registerEnvironment(id, new NumenTestEnvironment(batch));
    }
}
