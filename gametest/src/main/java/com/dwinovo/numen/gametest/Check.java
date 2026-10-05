package com.dwinovo.numen.gametest;

import net.minecraft.network.chat.Component;

/** 用例里写断言的小零件:原版的断言与失败都收 {@link Component},这里把一句话变成它。 */
public final class Check {

    private Check() {}

    public static Component text(String message) {
        return Component.literal(message);
    }
}
