package com.dwinovo.numen.bench;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 模拟主人的连接:没有客户端,下行包里模组自己的那些(自定义载荷)截下来交给评测大脑,其余丢掉。与同伴的
 * {@code FakeConnection} 同构(内存通道、回环地址、不跑心跳、不断线),多的只是截包。
 *
 * <h2>为什么下行包过得了 NeoForge 的频道检查</h2>
 * 发往玩家的自定义载荷在 {@code ServerCommonPacketListenerImpl.send} 里先过 {@code NetworkRegistry.checkPacket}:连接的
 * 频道表里没有它就抛 {@code UnsupportedOperationException}。同伴的连接过得去,是因为 numen 的 mixin 在那一步之前就把
 * 发往 {@code FakeConnection} 的包整个丢了——检查根本没跑。这条连接要收包,不能被丢,所以得真有一张频道表:
 * {@link NetworkRegistry#configureMockConnection} 是 NeoForge 给 GameTest 的现成做法,按"两边都是 NeoForge、登记过的
 * 频道全都协商好了"写上,和一个装齐了模组的真客户端协商出来的一样。
 */
final class OwnerConnection extends Connection {

    private static final InetSocketAddress LOOPBACK = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final Consumer<CustomPacketPayload> sink;

    private OwnerConnection(Consumer<CustomPacketPayload> sink) {
        super(PacketFlow.SERVERBOUND);
        this.sink = sink;
        // 把自己挂成内存通道的处理器:channelActive 给连接设上通道,placeNewPlayer 配置管线时要它
        new EmbeddedChannel(this);
        NetworkRegistry.configureMockConnection(this);
    }

    /**
     * 让一个主人上线,站到 {@code at}。
     *
     * @param sink 截下的模组载荷交给谁;在服务端主线程上调
     */
    static ServerPlayer join(MinecraftServer server, ServerLevel level, String name, Vec3 at,
                             Consumer<CustomPacketPayload> sink) {
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        ServerPlayer owner = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        server.getPlayerList().placeNewPlayer(new OwnerConnection(sink), owner,
                CommonListenerCookie.createInitial(profile, false));
        owner.teleportTo(level, at.x, at.y, at.z, 0, 0);
        return owner;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return LOOPBACK;
    }

    /** 模组的载荷交出去;原版的包(区块、位置、心跳)没人要,丢掉。 */
    @Override
    public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) {
            sink.accept(custom.payload());
        }
    }

    @Override
    public void runOnceConnected(Consumer<Connection> action) {
        // 不会"连上":什么都不跑
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    /** 不驱动监听器的 tick(那里跑心跳超时)。 */
    @Override
    public void tick() {
    }

    @Override
    public void disconnect(Component message) {
        // 主人随运行器离开,不从线上断
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
