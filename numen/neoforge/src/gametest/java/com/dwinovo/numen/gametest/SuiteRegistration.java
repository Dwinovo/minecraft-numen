package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.GameTestSuites;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;

/**
 * 加载器在 GameTest 上只做这一件事:把套件入口 {@link GameTestSuites} 登记给原版的 GameTest 框架。各模块的用例是原版的
 * {@code @GameTest},经 {@code GameTestSuite} 报到,选哪些由系统属性 {@code numen.gametest.suites} 定。
 */
@EventBusSubscriber(modid = "numen", bus = EventBusSubscriber.Bus.MOD)
public final class SuiteRegistration {

    private SuiteRegistration() {}

    @SubscribeEvent
    public static void register(RegisterGameTestsEvent event) {
        event.register(GameTestSuites.class);
    }
}
