package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.permission.PlacedBlocks;
import com.dwinovo.numen.api.program.ServerPrograms;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;

/**
 * 选中的 {@link GameTestSuite} 的用例,读成 {@link TestFunction};{@link GameTestRegistration} 把它们登记成原版的测试实例,
 * 批次登记成测试环境。用例本身只挂这个包里的注解({@link GameTest}、{@link BeforeBatch}、{@link AfterBatch}、
 * {@link GameTestGenerator}),不挂任何加载器的注解。
 *
 * <h2>跑哪些</h2>
 * 运行配置明确给出:系统属性 {@value #SELECTION} 是逗号分隔的套件名,没给或是空白就一个套件都不登记。套件按名字找,每个模块在自己的
 * gametest 资源里放一个名字唯一的 {@code META-INF/numen/gametest/套件名},内容是套件类({@link GameTestSuite} 的实现)的全名——
 * 名字各不相同,几个模块的输出合在一个模组里也都看得见。B 依赖 A 时,跑 B 的运行配置不选 A 的套件。选中了、找不到资源的套件名
 * (多半是手误)会在日志里说一次。加载器自己的用例(只有加载器的类看得见的事)经 {@link #loaderTests} 交进来,不属于任何套件,
 * 每次都跑。
 *
 * <h2>用例的名字</h2>
 * 测试名 {@code 套件名.小写类名.小写方法名};结构模板写了 {@code template} 就是 {@code 套件名:模板}(已带冒号的原样),没写就是
 * {@code 套件名:小写类名.小写方法名}。
 *
 * <h2>快用例与慢批</h2>
 * 套件的 {@link GameTestSuite#pace()} 决定收哪一档:日常批({@code FAST})只收时限不超过 {@value #FAST_TICKS} 刻的用例,超过的归同一个模块的
 * 慢批({@code SLOW},套件名是日常批的名字加 {@value #SLOW_SUFFIX})。时限就是唯一的判据,不另做标记;慢批的用例名与结构模板仍用日常批的套件名。
 *
 * <h2>排查与证明稳定</h2>
 * 系统属性 {@value #ONLY} 是正则,只留全名整个匹配的用例;{@value #COPIES} 是份数 N,每条留下的用例在同一次开服里铺 N 份并行跑
 * (第 2 份起的名字加 {@code #份号}),用来证明稳定:被怀疑的用例铺开跑,一次开服看全部结果,不重启服务器连跑。
 * 每条用例收场时在日志里记一行实际用了多少刻({@link TestTicks}),超时设多少有据可依。
 */
public final class GameTestSuites {

    /** 选套件的系统属性。 */
    public static final String SELECTION = "numen.gametest.suites";
    /** 只跑全名匹配的用例:正则,要整个匹配 {@code 套件名.小写类名.小写方法名}。 */
    public static final String ONLY = "numen.gametest.only";
    /** 每条用例铺几份并行跑(默认 1)。 */
    public static final String COPIES = "numen.gametest.copies";
    /** 结构模板目录的系统属性:各模块的 SNBT 模板拷进同一个目录,运行配置经它指给原版。 */
    public static final String STRUCTURES = "numen.gametest.structures";

    /** 套件登记的资源路径前缀,后面接套件名。 */
    private static final String REGISTRY = "META-INF/numen/gametest/";

    private static final Logger LOG = LogUtils.getLogger();

    /** 加载器自己的用例类(见 {@link #loaderTests})。 */
    private static final List<Class<?>> LOADER_TESTS = new ArrayList<>();
    /** 这一次运行的用例,第一次要时读出来。 */
    private static List<TestFunction> functions;
    /** 批次名 → 批前、批后的方法,按登记先后。 */
    private static final Map<String, List<Method>> BEFORE = new LinkedHashMap<>();
    private static final Map<String, List<Method>> AFTER = new LinkedHashMap<>();

    private GameTestSuites() {}

    /** 加载器在服务器起来之前交进来的、只有它的类看得见的用例类:不属于任何套件,每次都跑,名字不带套件前缀。 */
    public static synchronized void loaderTests(Class<?> type) {
        if (functions != null) {
            throw new IllegalStateException("用例已经读过了,加载器的用例要在服务器起来之前交");
        }
        LOADER_TESTS.add(type);
    }

    /** 套件 {@code suite}(或它的慢批)这一次被明确选中了;夹具在类加载时用它判断要不要登记只给这个套件用的东西。 */
    public static boolean selected(String suite) {
        return selection().stream().anyMatch(name -> base(name).equals(suite));
    }

    private static List<String> selection() {
        String property = System.getProperty(SELECTION);
        return property == null ? List.of()
                : Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /**
     * 这一次运行的全部用例:第一次调用时读出选中的套件,并装好整次运行共用的东西(测试库、模板目录、每刻等后台、开批对齐、收场汇报)。
     * 原版在加载数据包的注册表时要它们({@link GameTestRegistration}),那时服务器还没开始走刻。
     */
    public static synchronized List<TestFunction> functions() {
        if (functions != null) {
            return functions;
        }
        TestLibraries.install();
        // 模板在登记之后才加载,目录赶在这之前指好
        String structures = System.getProperty(STRUCTURES);
        if (structures != null) {
            StructureUtils.testStructuresDir = Path.of(structures);
        }
        List<TestFunction> out = new ArrayList<>();
        List<Supplier<String>> background = new ArrayList<>();
        background.add(ServerPrograms::busy);
        background.add(com.dwinovo.numen.api.brain.ServerSkins::busy);
        background.add(() -> com.dwinovo.numen.api.NumenIo.lanes().busy());
        background.add(() -> com.dwinovo.numen.api.mcp.server.McpMode.instance().closing());
        ServerPrograms.meter(task -> {
            task.run();
            return PROGRAM_CALL_NANOS;
        });
        for (String name : selection()) {
            GameTestSuite suite = find(name);
            if (suite == null) {
                LOG.warn("[numen-gametest] 选了套件 {},类路径上找不到 {}{}", name, REGISTRY, name);
                continue;
            }
            background.addAll(suite.background());
            List<TestFunction> own = new ArrayList<>();
            for (Class<?> type : suite.classes()) {
                collect(base(name), type, own);
            }
            own.stream().filter(f -> admits(suite.pace(), f)).forEach(out::add);
        }
        BackgroundSettle.install(background);
        BatchStart.install();
        GlobalTestReporter.replaceWith(new TestTicks(new LogTestReporter()));
        List<TestFunction> all = spread(out);
        for (Class<?> type : LOADER_TESTS) {
            collect(null, type, all);
        }
        functions = List.copyOf(all);
        return functions;
    }

    /** 批次 {@code batch} 开跑前的准备(测试环境的 {@code setup} 调)。 */
    static void beforeBatch(String batch, ServerLevel level) {
        BEFORE.getOrDefault(batch, List.of()).forEach(method -> invoke(method, level));
    }

    /** 批次 {@code batch} 收场后的善后(测试环境的 {@code teardown} 调)。 */
    static void afterBatch(String batch, ServerLevel level) {
        AFTER.getOrDefault(batch, List.of()).forEach(method -> invoke(method, level));
    }

    /**
     * 测试服里程序的一次服务端调用记多少纳秒预算(正式运行按墙钟量):一刻的总预算 10 毫秒够两百次,一段程序的 5 毫秒够一百次。
     * 一刻里一段程序只执行这一刻开始时排着的调用(一般一个),这个数让预算在任何并发份数下都不起作用,一刻做到哪只看调用的先后。
     */
    static final long PROGRAM_CALL_NANOS = 50_000;

    /** 快用例的时限上限(刻):日常批只收不超过它的,超过的归慢批。 */
    public static final int FAST_TICKS = 600;
    /** 慢批的套件名是日常批的名字加这个后缀;用例名、结构模板的命名空间与夹具的开关都认日常批的名字({@link #base})。 */
    public static final String SLOW_SUFFIX = "_slow";

    /** 套件名去掉慢批后缀:用例名与结构模板的命名空间。 */
    private static String base(String name) {
        return name.endsWith(SLOW_SUFFIX) ? name.substring(0, name.length() - SLOW_SUFFIX.length()) : name;
    }

    private static boolean admits(GameTestSuite.Pace pace, TestFunction f) {
        return switch (pace) {
            case ANY -> true;
            case FAST -> f.maxTicks() <= FAST_TICKS;
            case SLOW -> f.maxTicks() > FAST_TICKS;
        };
    }

    /** 按 {@value #ONLY} 筛、按 {@value #COPIES} 铺份;每份的函数体前记下起始刻,收场时 {@link TestTicks} 用它算用了多少刻。 */
    private static List<TestFunction> spread(List<TestFunction> functions) {
        String only = System.getProperty(ONLY);
        int copies = Integer.getInteger(COPIES, 1);
        List<TestFunction> out = new ArrayList<>();
        for (TestFunction function : functions) {
            if (only != null && !function.testName().matches(only)) {
                continue;
            }
            for (int copy = 1; copy <= copies; copy++) {
                out.add(timed(function, copy == 1 ? function.testName() : function.testName() + "#" + copy));
            }
        }
        return out;
    }

    private static TestFunction timed(TestFunction f, String name) {
        Consumer<GameTestHelper> body = helper -> {
            TestTicks.started(name, helper.getLevel().getGameTime());
            TestCleanup.started(helper, name);
            f.function().accept(helper);
        };
        return new TestFunction(f.batchName(), name, f.structureName(), f.rotation(), f.maxTicks(), f.setupTicks(),
                f.required(), f.skyAccess(), body);
    }

    /** 名字对应的套件:资源 {@code META-INF/numen/gametest/名字} 里写着套件类的全名;没有这个资源是 null。 */
    private static GameTestSuite find(String name) {
        ClassLoader loader = GameTestSuites.class.getClassLoader();
        try (InputStream registry = loader.getResourceAsStream(REGISTRY + name)) {
            if (registry == null) {
                return null;
            }
            String type = new String(registry.readAllBytes(), StandardCharsets.UTF_8).trim();
            return (GameTestSuite) Class.forName(type, true, loader).getDeclaredConstructor().newInstance();
        } catch (IOException | ReflectiveOperationException e) {
            throw new IllegalStateException("套件 " + name + " 读不出来", e);
        }
    }

    /** {@code suite} 为 null 是加载器自己的用例:名字不带套件前缀,结构模板要写全命名空间。 */
    private static void collect(String suite, Class<?> type, List<TestFunction> out) {
        // 用例类的静态块登记夹具(只给用例用的命令组、任务),要赶在服务器起来之前,所以此刻就初始化
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        Method[] methods = type.getDeclaredMethods();
        Arrays.sort(methods, Comparator.comparing(Method::getName));
        for (Method method : methods) {
            GameTest test = method.getAnnotation(GameTest.class);
            if (test != null) {
                out.add(function(suite, test, method));
            }
            if (method.isAnnotationPresent(GameTestGenerator.class)) {
                out.addAll(generated(method));
            }
            // 日常批与慢批共用一份用例类,批前批后的方法只登记一次
            BeforeBatch before = method.getAnnotation(BeforeBatch.class);
            if (before != null) {
                hook(BEFORE, before.batch(), method);
            }
            AfterBatch after = method.getAnnotation(AfterBatch.class);
            if (after != null) {
                hook(AFTER, after.batch(), method);
            }
        }
    }

    private static void hook(Map<String, List<Method>> hooks, String batch, Method method) {
        requireStatic(method);
        List<Method> methods = hooks.computeIfAbsent(batch, b -> new ArrayList<>());
        if (!methods.contains(method)) {
            methods.add(method);
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<TestFunction> generated(Method method) {
        requireStatic(method);
        return (Collection<TestFunction>) invoke(method);
    }

    private static TestFunction function(String suite, GameTest test, Method method) {
        requireStatic(method);
        String owner = method.getDeclaringClass().getSimpleName().toLowerCase();
        String name = owner + "." + method.getName().toLowerCase();
        String template = test.template().isEmpty() ? suite + ":" + name
                : test.template().contains(":") ? test.template() : suite + ":" + test.template();
        Consumer<GameTestHelper> body = helper -> {
            forgetMarks(helper);
            invoke(method, helper);
        };
        return new TestFunction(test.batch(), suite == null ? name : suite + "." + name, template,
                StructureUtils.getRotationForRotationSteps(test.rotationSteps()), test.timeoutTicks(), test.setupTicks(),
                test.required(), test.skyAccess(), body);
    }

    /**
     * 这个场景占的地方上,别的用例留下的放置记号全抹掉。场景是整块清掉再摆的,记号表(随世界存的 {@link PlacedBlocks})不会跟着清;
     * 同一处后来摆上的石头会被当成"建造的方块",走路规则不许挖({@code Walking.spec}),用例就挖不到本该挖的格子。
     */
    private static void forgetMarks(GameTestHelper helper) {
        PlacedBlocks marks = PlacedBlocks.of(helper.getLevel());
        BlockState anything = Blocks.STONE.defaultBlockState();
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(helper.getBounds().getMinPosition()),
                BlockPos.containing(helper.getBounds().getMaxPosition()))) {
            if (marks.placerAt(pos, anything) != null) {
                marks.forget(pos.immutable());
            }
        }
    }

    private static void requireStatic(Method method) {
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("GameTest 套件里的方法要是静态的:" + method);
        }
    }

    /** 调静态方法,把原因原样抛出:{@code GameTestAssertException} 照常让这条用例失败。 */
    private static Object invoke(Method method, Object... arguments) {
        try {
            return method.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(method.toString(), e);
        }
    }
}
