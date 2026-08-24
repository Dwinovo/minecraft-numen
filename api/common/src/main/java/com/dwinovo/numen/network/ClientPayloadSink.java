package com.dwinovo.numen.network;

import com.dwinovo.numen.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.network.payload.CurrentTaskPayload;
import com.dwinovo.numen.network.payload.CompanionListPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenStatePayload;
import com.dwinovo.numen.network.payload.NumenLocationsPayload;
import com.dwinovo.numen.network.payload.NumenRespawnPayload;
import com.dwinovo.numen.network.payload.PathDebugPayload;

import java.util.function.Consumer;

/**
 * 下行(S2C)payload 的客户端处理挂点。record 与编解码器必须留在主源码集
 * (服务端也要注册与发送),但处理体要摸客户端(agent loop、HUD、界面)——
 * 处理体住在客户端源码集的 {@code ClientPayloadHandlers},客户端入口启动时
 * {@code install()} 把它们挂进来。专用服务端从不收下行包,缺省 no-op 只是
 * 防御;这层间接正是"主源码集编译期摸不到客户端类"的关键一环。
 */
public final class ClientPayloadSink {

    private ClientPayloadSink() {}

    public static volatile Consumer<CompanionListPayload> companionList = p -> {};
    public static volatile Consumer<CurrentTaskPayload> currentTask = p -> {};
    public static volatile Consumer<NumenDeathPayload> death = p -> {};
    public static volatile Consumer<NumenEventPayload> event = p -> {};
    public static volatile Consumer<NumenStatePayload> state = p -> {};
    public static volatile Consumer<NumenLocationsPayload> locations = p -> {};
    public static volatile Consumer<NumenRespawnPayload> respawn = p -> {};
    public static volatile Consumer<PathDebugPayload> pathDebug = p -> {};
    public static volatile Consumer<ClientUiActionPayload> uiAction = p -> {};
}
