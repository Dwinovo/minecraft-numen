package com.dwinovo.numen.entity;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 假连接必须报一个<b>看得懂的</b>远端地址。
 *
 * <p>内存通道的远端地址是 netty 自己的 {@code EmbeddedSocketAddress},而生态里大量代码把
 * {@code SocketAddress} 直接强转成 {@link InetSocketAddress}。转失败抛在登录流程中途,
 * 表现是<b>真玩家进不去服务器</b>、报"无效的玩家数据"——存档没坏,是登录被打断了。
 * 这类崩溃只在装了别的模组时才出现,本仓自己怎么跑都测不到,所以钉在这儿。
 */
@Tag("mc")
class FakeConnectionAddressTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @Test
    void theRemoteAddressIsInetShapedSoThirdPartyCastsSurvive() {
        assumeTrue(booted);
        SocketAddress address = new FakeConnection().getRemoteAddress();
        assertInstanceOf(InetSocketAddress.class, address,
                "第三方模组会把它强转成 InetSocketAddress —— 转不了就在登录中途炸");
        assertTrue(((InetSocketAddress) address).getAddress().isLoopbackAddress());
    }

    @Test
    void everyCompanionReportsTheSameLoopbackAddress() {
        assumeTrue(booted);
        assertEquals(new FakeConnection().getRemoteAddress(),
                new FakeConnection().getRemoteAddress());
    }

    private static void assertTrue(boolean condition) {
        org.junit.jupiter.api.Assertions.assertTrue(condition, "地址应当是回环");
    }
}
