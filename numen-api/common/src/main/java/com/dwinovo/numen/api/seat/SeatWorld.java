package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.program.ProgramHost;
import com.google.gson.JsonObject;

import java.util.UUID;
import java.util.concurrent.Executor;

/**
 * 座位要用到的服务端的世界:谁创建了她、她此刻的样子、程序交给谁跑、她的话怎么说出口、她能做什么。座位自己不碰 Minecraft,
 * 产品里是 {@code ServerSeatWorld},单测与没有服务器的进程里是各自的实现。
 *
 * <p>线程:除 {@link #main}、{@link #catalog} 外都只在 {@link #main} 上调。
 */
public interface SeatWorld {

    /** 服务端主线程。 */
    Executor main();

    /** 这位玩家是不是这只同伴的创建者,并且装着可用的客户端:只有这样的连接坐得上她的座位。 */
    boolean mayOpen(UUID player, UUID companion);

    /** 她此刻的样子,读身体写成;只在 {@link #main} 上调。 */
    SeatState state(UUID companion);

    /** 主世界的游戏刻。 */
    long gameTime();

    /** 主世界的时刻({@code dayTime}),事件盖时间戳用。 */
    long dayTime();

    /** 程序、叫停与切断交给谁。 */
    ProgramHost programs();

    /** 她的话在聊天里说出来。 */
    void say(UUID companion, String text);

    /** 她能做什么:注册表里服务端拥有的那些,含 {@code version}。任何线程。 */
    JsonObject catalog(UUID companion);
}
