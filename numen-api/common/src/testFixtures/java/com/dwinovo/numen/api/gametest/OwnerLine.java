package com.dwinovo.numen.api.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 在场主人的连接:没有客户端,下行的模组载荷记在 {@link #sent},别的包丢掉。内存通道、回环地址、不跑心跳、不断线。
 * 载荷过得了频道检查,靠 {@link MockConnection} 给它配的频道表。
 */
public final class OwnerLine extends Connection {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    /** 服务端发给这位主人的模组载荷,按先后。 */
    public final Queue<CustomPacketPayload> sent = new ConcurrentLinkedQueue<>();

    public OwnerLine() {
        super(PacketFlow.SERVERBOUND);
        // 把自己挂成内存通道的处理器:channelActive 给连接设上通道,placeNewPlayer 配置管线时要它
        new EmbeddedChannel(this);
        ServiceLoader.load(MockConnection.class, MockConnection.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("没有 MockConnection 的实现:加载器的开发期源码集要提供一个"))
                .configure(this);
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            sent.add(custom.payload());
        }
    }

    @Override
    public void runOnceConnected(Consumer<Connection> action) {
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public void tick() {
    }

    @Override
    public void disconnect(Component message) {
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void setReadOnly() {
    }
}
