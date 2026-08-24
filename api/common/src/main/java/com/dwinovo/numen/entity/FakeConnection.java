package com.dwinovo.numen.entity;

import net.minecraft.network.PacketSendListener;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A channel-less {@link Connection} for a companion fake {@code ServerPlayer}.
 * The server pushes a steady stream of clientbound packets at any list-resident
 * player (position, chunks, entity updates, keep-alives); a real connection
 * writes them to a netty channel, but our companion has no client on the other
 * end. A bare {@code new Connection(...)} is NOT safe: its {@code send} queues
 * every packet into an unbounded {@code pendingActions} list when no channel is
 * attached — a slow leak. So we subclass and DISCARD all outbound I/O.
 *
 * <p>{@code Connection} is non-final with a public {@code PacketFlow} ctor, so
 * this is pure common code — no reflection, access-wideners or mixins. Two
 * details make {@code placeNewPlayer} accept it:
 * <ul>
 *   <li><b>SERVERBOUND</b> receiving flow — a server-side player connection
 *       receives serverbound packets (the client sends them), so its listener
 *       is serverbound; {@code validateListener} rejects a CLIENTBOUND mismatch.</li>
 *   <li>a real (in-memory) {@link EmbeddedChannel} — {@code placeNewPlayer ->
 *       setupInboundProtocol} configures the channel's pipeline, which NPEs on a
 *       channel-less connection. Registering this Connection as the embedded
 *       channel's handler fires {@code channelActive}, setting the channel.</li>
 * </ul>
 * Outbound packets are still discarded by the {@code send} override (the
 * embedded channel is never written to, so it never buffers/leaks), and the
 * keep-alive timeout is neutralised via the no-op {@code disconnect} (a fake
 * player never answers keep-alive). Lifecycle is governed by
 * {@code CompanionLifecycle}, not by connection state.
 *
 * <p>1.20.2 introduced the configuration phase: the packet codec now lives in
 * channel ATTRIBUTES ({@code ATTRIBUTE_*_PROTOCOL}), and
 * {@code ServerGamePacketListenerImpl}'s ctor reads them immediately — a bare
 * embedded channel NPEs there, so both attributes are seeded with the PLAY
 * codec below.
 */
@com.dwinovo.numen.api.Internal
public final class FakeConnection extends Connection {

    public FakeConnection() {
        super(PacketFlow.SERVERBOUND);
        // Registering this Connection (a netty inbound handler) as the embedded
        // channel's handler fires channelActive → sets this.channel, so the
        // pipeline setup inside placeNewPlayer has a channel to work on.
        EmbeddedChannel ch = new EmbeddedChannel(this);
        // 1.20.2 起协议编解码挂在 channel attribute 上,placeNewPlayer →
        // ServerGamePacketListenerImpl 构造即读取,不种直接 NPE。
        ch.attr(Connection.ATTRIBUTE_SERVERBOUND_PROTOCOL)
                .set(net.minecraft.network.ConnectionProtocol.PLAY.codec(PacketFlow.SERVERBOUND));
        ch.attr(Connection.ATTRIBUTE_CLIENTBOUND_PROTOCOL)
                .set(net.minecraft.network.ConnectionProtocol.PLAY.codec(PacketFlow.CLIENTBOUND));
    }

    /** 同伴连接对外报的地址。见 {@link #getRemoteAddress}。 */
    private static final InetSocketAddress LOOPBACK =
            new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    /**
     * 回环地址。<b>不是装饰,是兼容性要求。</b>
     *
     * <p>内存通道的远端地址是 netty 自己的类型,而生态里大量代码把 {@code SocketAddress}
     * <b>裸强转</b>成 {@link InetSocketAddress}(IP 白名单、登录风控、聊天桥接都这么写)。
     * 转失败抛在玩家加入事件里、打断整个登录流程,表现是<b>真玩家也进不去服务器</b>,
     * 客户端显示"无效的玩家数据"——存档没坏,是登录被异常中断了。这类崩溃只在装了别的
     * 模组时才出现,本仓自己怎么跑都测不到。
     *
     * <p>不是我们独有的坑:原版单人世界与"对局域网开放"走的也是内存通道
     * ({@code ServerConnectionListener.startMemoryChannel},地址是 netty 的
     * {@code LocalAddress}),那些裸强转在原版面前一样炸。但能修的只有我们这一侧。
     *
     * <p>回环是<b>诚实</b>的答案:这具身体确实就在本机,没有远端。真玩家报的是公网 IP,
     * 服主看日志一眼分得开。
     */
    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    /** Discard every outbound packet — there is no client to receive it.
     *  The 1-arg and 2-arg {@code send} overloads route through this one. */
    @Override
    public void send(Packet<?> packet, PacketSendListener listener) {
        // no-op: drop it on the floor (no channel, no pendingActions growth)
    }

    /**
     * Report live so player ticking / chunk tracking proceed as for a real
     * player. Safe because every channel-dereferencing path (send, tick,
     * flushChannel) is overridden to no-op below, so the null channel is never
     * touched.
     */
    @Override
    public boolean isConnected() {
        return true;
    }

    /** Don't drive the packet listener's tick (that's what runs the keep-alive). */
    @Override
    public void tick() {
        // no-op
    }

    /** Neutralise the keep-alive timeout (and any other) disconnect — both overloads. */
    @Override
    public void disconnect(Component message) {
        // no-op: the companion is removed via CompanionLifecycle, never by the wire
    }

    @Override
    public void handleDisconnection() {
        // no-op
    }

    @Override
    public void setReadOnly() {
        // no-op
    }
}
