package com.dwinovo.numen.pathing.gametest;

import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.network.chat.Component;

/** 用例里手抛的断言失败:原版 26.1 的断言异常带的是 {@link Component} 与出错的刻,这里收成一句话。 */
public final class NumenAssertion {

    private NumenAssertion() {}

    public static GameTestAssertException failed(String message) {
        return new GameTestAssertException(Component.literal(message), 0);
    }
}
