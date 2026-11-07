package com.dwinovo.numen.api.gametest;

import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.util.UUID;

/**
 * 原版客户端进得来:没装我们的客户端只是没有我们的可选频道,不是协商失败。NeoForge 在配置阶段对"不是 NeoForge 的客户端"
 * 跑的就是 {@code NetworkRegistry.initializeOtherConnection}——服务端登记的频道里有一条不是可选的,它就把客户端踢出去。
 * 这里对着本进程里登记的全部频道原样跑一遍这个协商,连接是没配过频道表的 {@link OwnerLine}(原版客户端的样子)。
 */
@PrefixGameTestTemplate(false)
public final class NeoForgeHandshakeTests {

    private NeoForgeHandshakeTests() {}

    @GameTest(templateNamespace = "numen", template = "floor16", batch = "numen_clientless")
    public static void a_vanilla_client_is_not_refused_by_our_channels(GameTestHelper helper) {
        OwnerLine vanilla = new OwnerLine(false);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "gametest_vanilla_joiner");
        var listener = new ServerConfigurationPacketListenerImpl(helper.getLevel().getServer(), vanilla,
                CommonListenerCookie.createInitial(profile, false));

        boolean served = NetworkRegistry.initializeOtherConnection(listener);

        helper.assertTrue(served && vanilla.disconnects.isEmpty(),
                "a vanilla client was refused: " + vanilla.disconnects);
        helper.succeed();
    }
}
