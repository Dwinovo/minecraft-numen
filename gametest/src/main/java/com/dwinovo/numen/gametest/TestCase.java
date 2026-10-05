package com.dwinovo.numen.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

import java.util.function.Consumer;

/**
 * 一条登记出来的用例,{@link NumenTestGenerator} 的产物;字段的含义同 {@link NumenTest}。
 *
 * @param name 用例名,命名空间之下的那一段
 */
public record TestCase(String name, String template, int timeoutTicks, String batch, Consumer<GameTestHelper> body) {
}
