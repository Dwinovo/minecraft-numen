package com.dwinovo.numen.api.gametest;

import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.game.ClientboundCustomChatCompletionsPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * 在场主人的连接:没有真客户端,下行的模组载荷记在 {@link #sent},系统聊天与动作栏记在 {@link #said},玩家聊天记在
 * {@link #chat},聊天补全记在 {@link #completions},别的包丢掉。内存通道、回环地址、不跑心跳、不断线。
 *
 * <p>装了我们的客户端的({@code installed})载荷过得了频道检查,靠 {@link MockConnection} 给它配的频道表;
 * 没装的是原版客户端的样子,频道表是空的。
 */
public final class OwnerLine extends Connection {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    /** 服务端发给这位主人的模组载荷,按先后。 */
    public final Queue<CustomPacketPayload> sent = new ConcurrentLinkedQueue<>();

    /** 服务端发给这位主人的系统聊天与动作栏,按先后。 */
    public record Said(Component text, boolean overlay) {}

    public final Queue<Said> said = new ConcurrentLinkedQueue<>();
    /** 公屏上别人说给他的话(玩家聊天包),按先后。 */
    public final Queue<ClientboundPlayerChatPacket> chat = new ConcurrentLinkedQueue<>();
    /** 服务端设给他聊天框的补全。 */
    public final Queue<ClientboundCustomChatCompletionsPacket> completions = new ConcurrentLinkedQueue<>();
    /** 服务端对这条连接说的断开理由。 */
    public final Queue<Component> disconnects = new ConcurrentLinkedQueue<>();

    /** @param installed 他装着我们的客户端(频道协商好了);否则是原版客户端 */
    public OwnerLine(boolean installed) {
        super(PacketFlow.SERVERBOUND);
        // 把自己挂成内存通道的处理器:channelActive 给连接设上通道,placeNewPlayer 配置管线时要它
        new EmbeddedChannel(this);
        if (installed) {
            MockConnection.INSTANCE.configure(this);
        }
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            sent.add(MockConnection.INSTANCE.received(custom.payload()));
        } else if (packet instanceof ClientboundSystemChatPacket chat) {
            said.add(new Said(chat.content(), chat.overlay()));
        } else if (packet instanceof ClientboundPlayerChatPacket line) {
            chat.add(line);
        } else if (packet instanceof ClientboundCustomChatCompletionsPacket completion) {
            completions.add(completion);
        } else if (packet instanceof ClientboundDisconnectPacket disconnect) {
            disconnects.add(disconnect.reason());
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
        disconnects.add(message);
    }

    @Override
    public void disconnect(DisconnectionDetails details) {
        disconnects.add(details.reason());
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
