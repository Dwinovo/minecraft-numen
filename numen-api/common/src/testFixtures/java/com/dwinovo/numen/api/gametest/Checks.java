package com.dwinovo.numen.api.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;

/**
 * 用例断言的文字口径。原版 1.21.5 起 {@code GameTestHelper} 的断言与失败只收 {@link Component},{@link GameTestAssertException} 还要带刻数;
 * 用例里的断言都是拼好的一句话,这里替它们包成字面文本,用例与夹具一律经这里断言。
 */
public final class Checks {

    private Checks() {}

    public static void assertTrue(GameTestHelper helper, boolean condition, String message) {
        helper.assertTrue(condition, Component.literal(message));
    }

    public static void assertFalse(GameTestHelper helper, boolean condition, String message) {
        helper.assertFalse(condition, Component.literal(message));
    }

    public static void fail(GameTestHelper helper, String message) {
        helper.fail(Component.literal(message));
    }

    /**
     * 断言不成立,由调用处抛出。这些断言多半在没有 helper 的地方(夹具、每刻的检查),刻数只是报告里的参考,记 0;
     * 用例在哪一刻失败,收场的那一行({@code [numen-gametest-ticks]})写着。
     */
    public static GameTestAssertException failure(String message) {
        return new GameTestAssertException(Component.literal(message), 0);
    }
}
