package com.dwinovo.numen.api.gametest;

import net.fabricmc.api.ModInitializer;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * 加载器在 GameTest 上只做这一件事:登记我们的测试实例与测试环境两个类型。用例本身由 {@link GameTestRegistration} 登记进原版的
 * 注册表(两个加载器同一条路),选哪些由系统属性 {@code numen.gametest.suites} 定。
 */
public final class FabricGameTestMod implements ModInitializer {

    @Override
    public void onInitialize() {
        Registry.register(BuiltInRegistries.TEST_INSTANCE_TYPE, NumenTestInstance.TYPE, NumenTestInstance.CODEC);
        Registry.register(BuiltInRegistries.TEST_ENVIRONMENT_DEFINITION_TYPE, NumenTestEnvironment.TYPE, NumenTestEnvironment.CODEC);
    }
}
