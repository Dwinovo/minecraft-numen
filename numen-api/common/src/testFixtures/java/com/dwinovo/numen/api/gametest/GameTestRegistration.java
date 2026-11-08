package com.dwinovo.numen.api.gametest;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.WritableRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestInstance;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

/**
 * 把 {@link GameTestSuites#functions()} 登记进原版的两个数据注册表:每个批次一个测试环境({@link NumenTestEnvironment}),每条用例一个
 * 测试实例({@link NumenTestInstance})。原版在加载数据包的注册表时一个个读,测试环境排在测试实例前面;每读完一个就交到这里
 * ({@code GameTestRegistrationMixin}),趁它还没冻结把我们的条目加进去。两个加载器走的是这同一条路,它们只替我们登记两个类型。
 *
 * <p>用例的注册名:命名空间是套件名(加载器自己的用例是 {@value #LOADER_NAMESPACE}),路径是全名去掉套件前缀,铺份的 {@code #份号}
 * 写成 {@code /份号}(注册名里没有 {@code #})。日志与汇报里仍用全名({@link NumenTestInstance#name})。
 */
public final class GameTestRegistration {

    /** 加载器自己的用例与批次环境的命名空间:各加载器的开发期小模组。 */
    static final String LOADER_NAMESPACE = "numen_api_gametest";

    /** 这一次读注册表时登记好的环境:批次名 → 环境。测试实例在环境之后读,拿这里的环境。 */
    private static final Map<String, Holder<TestEnvironmentDefinition>> ENVIRONMENTS = new HashMap<>();

    private GameTestRegistration() {}

    /** 原版读完了一个数据注册表、还没冻结:是测试环境或测试实例的,把我们的加进去。 */
    @SuppressWarnings("unchecked")
    public static void loaded(WritableRegistry<?> registry) {
        if (registry.key() == Registries.TEST_ENVIRONMENT) {
            environments((WritableRegistry<TestEnvironmentDefinition>) registry, GameTestSuites.functions());
        } else if (registry.key() == Registries.TEST_INSTANCE) {
            instances((WritableRegistry<GameTestInstance>) registry, GameTestSuites.functions());
        }
    }

    /** 名字是 {@code name} 的那条用例(测试实例从客户端同步回来解码时要)。 */
    static TestFunction function(String name) {
        return GameTestSuites.functions().stream().filter(f -> f.testName().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalStateException("没有这条用例:" + name));
    }

    private static void environments(WritableRegistry<TestEnvironmentDefinition> registry, List<TestFunction> functions) {
        ENVIRONMENTS.clear();
        for (TestFunction function : functions) {
            ENVIRONMENTS.computeIfAbsent(function.batchName(), batch -> registry.register(
                    ResourceKey.create(Registries.TEST_ENVIRONMENT,
                            ResourceLocation.fromNamespaceAndPath(LOADER_NAMESPACE, "batch/" + batch.toLowerCase(Locale.ROOT))),
                    new NumenTestEnvironment(batch), RegistrationInfo.BUILT_IN));
        }
    }

    private static void instances(WritableRegistry<GameTestInstance> registry, List<TestFunction> functions) {
        for (TestFunction function : functions) {
            TestData<Holder<TestEnvironmentDefinition>> data = new TestData<>(ENVIRONMENTS.get(function.batchName()),
                    ResourceLocation.parse(function.structureName()), function.maxTicks(), (int) function.setupTicks(),
                    function.required(), function.rotation(), false, 1, 1, function.skyAccess());
            registry.register(ResourceKey.create(Registries.TEST_INSTANCE, id(function.testName())),
                    new NumenTestInstance(function, data), RegistrationInfo.BUILT_IN);
        }
    }

    private static ResourceLocation id(String testName) {
        String name = testName.replace('#', '/');
        int dot = name.indexOf('.');
        String suite = name.substring(0, dot);
        // 加载器自己的用例名不带套件前缀,第一段是类名
        return GameTestSuites.selected(suite)
                ? ResourceLocation.fromNamespaceAndPath(suite, name.substring(dot + 1))
                : ResourceLocation.fromNamespaceAndPath(LOADER_NAMESPACE, name);
    }
}
