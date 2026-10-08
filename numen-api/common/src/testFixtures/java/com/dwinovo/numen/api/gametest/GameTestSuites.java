package com.dwinovo.numen.api.gametest;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.gametest.framework.TestFunction;
import org.slf4j.Logger;

/**
 * 选中的 {@link GameTestSuite} 的用例,经一个原版的 {@code @GameTestGenerator} 交给 GameTest 框架。加载器只要把这个类登记给
 * 原版的 GameTest 框架(见各加载器的开发期源码集),用例本身不挂任何加载器的注解。
 *
 * <h2>跑哪些</h2>
 * 运行配置明确给出:系统属性 {@value #SELECTION} 是逗号分隔的套件名,没给或是空白就一个套件都不登记。套件按名字找,每个模块在自己的
 * gametest 资源里放一个名字唯一的 {@code META-INF/numen/gametest/套件名},内容是套件类({@link GameTestSuite} 的实现)的全名——
 * 名字各不相同,几个模块的输出合在一个模组里也都看得见。B 依赖 A 时,跑 B 的运行配置不选 A 的套件。选中了、找不到资源的套件名
 * (多半是手误)会在日志里说一次。
 *
 * <h2>用例的名字</h2>
 * 测试名 {@code 套件名.小写类名.小写方法名};结构模板写了 {@code template} 就是 {@code 套件名:模板}(已带冒号的原样),没写就是
 * {@code 套件名:小写类名.小写方法名}。
 */
public final class GameTestSuites {

    /** 选套件的系统属性。 */
    public static final String SELECTION = "numen.gametest.suites";
    /** 结构模板目录的系统属性:各模块的 SNBT 模板拷进同一个目录,运行配置经它指给原版。 */
    public static final String STRUCTURES = "numen.gametest.structures";

    /** 套件登记的资源路径前缀,后面接套件名。 */
    private static final String REGISTRY = "META-INF/numen/gametest/";

    private static final Logger LOG = LogUtils.getLogger();

    /** 公开的无参构造:原版调生成器方法前先实例化声明它的类,各加载器登记入口也要实例化它。 */
    public GameTestSuites() {}

    /** 套件 {@code suite} 这一次被明确选中了;夹具在类加载时用它判断要不要登记只给这个套件用的东西。 */
    public static boolean selected(String suite) {
        return selection().contains(suite);
    }

    private static List<String> selection() {
        String property = System.getProperty(SELECTION);
        return property == null ? List.of()
                : Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    @GameTestGenerator
    public static Collection<TestFunction> all() {
        // 模板在登记之后才加载,目录赶在这之前指好
        String structures = System.getProperty(STRUCTURES);
        if (structures != null) {
            StructureUtils.testStructuresDir = structures;
        }
        List<TestFunction> out = new ArrayList<>();
        for (String name : selection()) {
            GameTestSuite suite = find(name);
            if (suite == null) {
                LOG.warn("[numen-gametest] 选了套件 {},类路径上找不到 {}{}", name, REGISTRY, name);
                continue;
            }
            for (Class<?> type : suite.classes()) {
                collect(name, type, out);
            }
        }
        return out;
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
            if (method.isAnnotationPresent(BeforeBatch.class) || method.isAnnotationPresent(AfterBatch.class)) {
                GameTestRegistry.register(method);
            }
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
        Consumer<GameTestHelper> body = helper -> invoke(method, helper);
        return new TestFunction(test.batch(), suite + "." + name, template,
                StructureUtils.getRotationForRotationSteps(test.rotationSteps()), test.timeoutTicks(), test.setupTicks(),
                test.required(), test.manualOnly(), test.attempts(), test.requiredSuccesses(), test.skyAccess(), body);
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
