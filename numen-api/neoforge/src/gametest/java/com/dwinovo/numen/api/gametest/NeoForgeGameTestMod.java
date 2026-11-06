package com.dwinovo.numen.api.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModLoader;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 加载器在 GameTest 上只做这一件事:把套件入口 {@link GameTestSuites} 登记给原版的 GameTest 框架。各模块的用例是原版的
 * {@code @GameTest},经 {@link GameTestSuite} 按名字报到,选哪些由系统属性 {@code numen.gametest.suites} 定。
 * 这个开发期的小模组只在 GameTest 与评测的运行配置里加载,不进发行 jar。
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
        modBus.addListener((RegisterGameTestsEvent event) -> {
            event.register(GameTestSuites.class);
            // NeoForge 的联网协商只有 NeoForge 的类看得见,这条用例放在这里,不进模块的套件
            event.register(NeoForgeHandshakeTests.class);
        });
    }
}
