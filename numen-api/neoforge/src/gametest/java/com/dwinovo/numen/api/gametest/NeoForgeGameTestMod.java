package com.dwinovo.numen.api.gametest;

import java.util.Arrays;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.registries.RegisterEvent;

/**
 * 加载器在 GameTest 上只做这两件事:登记我们的测试实例与测试环境两个类型(原版的类型注册表要经加载器的登记事件写),和交进只有
 * NeoForge 的类看得见的用例。用例本身由 {@link GameTestRegistration} 登记进原版的注册表(两个加载器同一条路),选哪些由系统属性
 * {@code numen.gametest.suites} 定。这个开发期的小模组只在 GameTest 与评测的运行配置里加载,不进发行 jar。
 *
 * <p>另一件事是让模组加载失败的运行以非零码退出:原版服务器的 {@code Main} 把启动时的异常(哪个模组构造时炸了)接住、记一行
 * "Failed to start the minecraft server" 就正常返回,进程退出码是 0,Gradle 当它成功了(Fabric 的加载器自己以非零码退出,没有这个问题)。
 * FML 把失败留在 {@link ModLoader#hasErrors()},退出时看它。
 */
@Mod("numen_api_gametest")
public final class NeoForgeGameTestMod {

    public NeoForgeGameTestMod(IEventBus modBus) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (ModLoader.hasErrors()) {
                Runtime.getRuntime().halt(1);
            }
        }, "numen-gametest-loading-errors"));
        modBus.addListener((RegisterEvent event) -> {
            event.register(Registries.TEST_INSTANCE_TYPE, NumenTestInstance.TYPE, () -> NumenTestInstance.CODEC);
            event.register(Registries.TEST_ENVIRONMENT_DEFINITION_TYPE, NumenTestEnvironment.TYPE, () -> NumenTestEnvironment.CODEC);
        });
        // NeoForge 的联网协商只有 NeoForge 的类看得见,这条用例放在这里,不进模块的套件。运行配置用
        // neoforge.enabledGameTestNamespaces 只开联动自己的命名空间时(联动那几次跑批,装着别的模组)不带它
        String enabled = System.getProperty("neoforge.enabledGameTestNamespaces");
        if (enabled == null || Arrays.asList(enabled.split(",")).contains("numen_api_gametest")) {
            GameTestSuites.loaderTests(NeoForgeHandshakeTests.class);
        }
    }
}
