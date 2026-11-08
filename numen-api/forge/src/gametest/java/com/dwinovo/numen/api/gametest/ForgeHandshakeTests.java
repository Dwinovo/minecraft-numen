package com.dwinovo.numen.api.gametest;

import java.util.Collection;
import java.util.List;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.network.NetworkRegistry;

/**
 * 原版客户端进得来:没装我们的客户端只是没有我们的可选频道,不是协商失败。Forge 在登录阶段对"不是 Forge 的客户端"问的就是
 * {@link NetworkRegistry#acceptsVanillaClientConnections()}——服务端登记的频道里有一条不接受对端缺席,它就把客户端踢出去。
 * 这里对着本进程里登记的全部频道原样问一遍。
 *
 * <p>用例经生成器交出:这样测试名与模板名与套件里的用例同一个写法({@code numen:floor16}),不经 Forge 按类给模板加的前缀。
 */
public final class ForgeHandshakeTests {

    private ForgeHandshakeTests() {}

    @GameTestGenerator
    public static Collection<TestFunction> tests() {
        return List.of(new TestFunction("numen_clientless",
                "numen_api_gametest.forgehandshaketests.a_vanilla_client_is_not_refused_by_our_channels",
                "numen:floor16", Rotation.NONE, 100, 0, true, 1, 1, helper -> {
                    helper.assertTrue(NetworkRegistry.acceptsVanillaClientConnections(),
                            "a vanilla client would be refused by one of the registered channels");
                    helper.succeed();
                }));
    }
}
