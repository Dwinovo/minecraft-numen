package com.dwinovo.numen.api.gametest;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 加载器在 GameTest 上只做这一件事:把套件入口 {@link GameTestSuites} 登记给原版的 GameTest 框架。各模块的用例是原版的
 * {@code @GameTest},经 {@link GameTestSuite} 按名字报到,选哪些由系统属性 {@code numen.gametest.suites} 定。
 * 这个开发期的小模组只在 GameTest 与评测的运行配置里加载,不进发行 jar。
 */
@Mod("numen_api_gametest")
public final class NeoForgeGameTestMod {

    public NeoForgeGameTestMod(IEventBus modBus) {
        modBus.addListener((RegisterGameTestsEvent event) -> event.register(GameTestSuites.class));
    }
}
