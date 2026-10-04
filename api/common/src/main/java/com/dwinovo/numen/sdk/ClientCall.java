package com.dwinovo.numen.sdk;

import java.util.UUID;

/**
 * 主人客户端上的一次调用:她是谁、调的是哪个函数。客户端函数拿到它,当场答。要她的循环、界面这类只活在客户端的东西,拿 UUID 去
 * 客户端那一侧的登记处取——公共代码摸不到客户端类。
 */
public final class ClientCall {

    private final UUID companion;
    private final ApiFunction function;
    private final Record args;

    ClientCall(UUID companion, ApiFunction function, Record args) {
        this.companion = companion;
        this.function = function;
        this.args = args;
    }

    /** 她是谁。 */
    public UUID companion() {
        return companion;
    }

    /** 这次调用写成的那一行 Lua。 */
    public String lua() {
        return Call.of(function, args);
    }
}
