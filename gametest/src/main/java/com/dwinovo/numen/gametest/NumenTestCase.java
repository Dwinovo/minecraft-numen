package com.dwinovo.numen.gametest;

import net.minecraft.gametest.framework.GameTestHelper;

import java.util.function.Consumer;

/**
 * {@link NumenGameTestGenerator} 现算出来的一条用例:名字、结构模板、超时、批次与用例体。
 *
 * @param name 用例名,编号取 {@code <命名空间>:<名字>},小写
 */
public record NumenTestCase(String name, String template, int timeoutTicks, String batch, Consumer<GameTestHelper> body) {
}
