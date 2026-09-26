package com.dwinovo.numen.core;

/**
 * core 的单测要读命令树时共用的一步:引导 MC(方块注册表,原语要认方块),再照 {@link NumenCore#init()} 同一份登记装上
 * core 的全部命令组与工具。命令树与工具表是进程级的静态表,一个进程只装一次;各测试类都经这里装,不各装各的一份——
 * 装两次会撞名。
 */
public final class CoreCommandsFixture {

    private static boolean installed;

    private CoreCommandsFixture() {}

    public static synchronized void install() {
        if (installed) {
            return;
        }
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        NumenCore.init();
        installed = true;
    }
}
