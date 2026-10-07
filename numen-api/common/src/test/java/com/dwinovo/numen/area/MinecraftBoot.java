package com.dwinovo.numen.area;

import com.mojang.brigadier.exceptions.BuiltInExceptionProvider;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

/**
 * 区域测试的无头引导。引导 MC 时 {@code SharedConstants} 把 Brigadier 的内置报错换成了 MC 的说法;测完换回引导之前的
 * 那一份,同一个 JVM 里读 Brigadier 原话的命令测试不随这几个类先跑还是后跑而变(与 {@code BrigadierHelpTest} 同一个做法)。
 */
final class MinecraftBoot {

    private static BuiltInExceptionProvider brigadierWords;

    private MinecraftBoot() {}

    /** 引导;失败返回 false(测试跳过而不失败)。 */
    static boolean boot() {
        brigadierWords = CommandSyntaxException.BUILT_IN_EXCEPTIONS;
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 换回引导之前 Brigadier 内置报错的说法。 */
    static void restoreBrigadierWords() {
        CommandSyntaxException.BUILT_IN_EXCEPTIONS = brigadierWords;
    }
}
