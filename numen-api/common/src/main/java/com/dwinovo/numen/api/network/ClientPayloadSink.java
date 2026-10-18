package com.dwinovo.numen.api.network;

import com.dwinovo.numen.api.network.payload.ClientUiActionPayload;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.FeedPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HistoryPagePayload;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.NumenStatePayload;
import com.dwinovo.numen.api.network.payload.NumenLocationsPayload;

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
    public static volatile Consumer<com.dwinovo.numen.api.network.payload.ConsentRequestPayload> consent = p -> {};
    public static volatile Consumer<NumenStatePayload> state = p -> {};
    public static volatile Consumer<NumenLocationsPayload> locations = p -> {};
    public static volatile Consumer<ClientUiActionPayload> uiAction = p -> {};
    public static volatile Consumer<HelloAckPayload> helloAck = p -> {};
    public static volatile Consumer<FeedPayload> feed = p -> {};
    public static volatile Consumer<HistoryPagePayload> historyPage = p -> {};
    public static volatile Consumer<ConversationsPayload> conversations = p -> {};
    public static volatile Consumer<LibraryPayload> library = p -> {};
    public static volatile Consumer<com.dwinovo.numen.api.network.payload.ExportChunkPayload> export = p -> {};
}
