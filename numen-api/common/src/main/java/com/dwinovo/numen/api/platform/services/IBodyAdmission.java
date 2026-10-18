package com.dwinovo.numen.api.platform.services;

import com.dwinovo.numen.api.entity.FakeConnection;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 平台接不接纳她这具身体,以及她加入以后平台要做的事。
 *
 * <p>她是个真玩家(经 {@code PlayerList.placeNewPlayer} 加入),可有的平台对"玩家进来"有自己的规矩:Paper 上登录插件
 * (权限、封禁、登录保护)靠 {@code AsyncPlayerPreLoginEvent} 与 {@code PlayerLoginEvent} 认识一个玩家,{@code placeNewPlayer}
 * 不发这两个事件;Paper 的区块系统还会把每个玩家当成区块的观察者。这些是平台的事,common 只问"接纳吗"、告诉平台"她进来了"。
 * 模组上当场接纳、什么也不做。
 */
public interface IBodyAdmission {

    /**
     * 造好了身体与它的连接、还没加入:平台接纳还是拒绝。答复经 {@code verdict} 交回,在服务端主线程上跑——模组上就在这个调用里,
     * 异步的平台(Paper 的登录事件要在异步线程上发)在以后的某一刻。{@code null} = 接纳;否则是说给玩家听的拒绝原因。
     * 平台要钩在连接上的东西(Paper:她收到的包交给 {@code FakeClient})也在这里钩。
     */
    void admit(NumenPlayer body, FakeConnection connection, Consumer<Component> verdict);

    /** 她刚加入了玩家列表(主线程,{@code placeNewPlayer} 刚返回、还没定位):平台在这时对她做它要做的事。 */
    void joined(NumenPlayer body);
}
