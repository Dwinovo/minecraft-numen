package com.dwinovo.numen.pathing.gametest;

import java.util.UUID;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.pathing.body.Controls;
import com.dwinovo.numen.pathing.body.Physics;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.GameType;

/**
 * 夹具自己的假玩家,不是同伴:一具普通的服务端玩家,带一副键盘({@link Controls}),每刻在自己的实体刻里跑模块的物理步进
 * ({@link Physics#step}),别的什么也不做。它能走通就说明寻路模块不依赖宿主。
 *
 * <p>它只进世界、不进玩家列表(不"登录"):没有客户端,也就没有与服务器协商过任何模组的网络通道,登录时别的模组发来的
 * 自定义包会被网络层当场拒掉。进了世界就有区块跟着它加载、实体刻照常跑;连接丢掉一切下行包。
 */
final class TestBody extends ServerPlayer implements Body {

    private final Controls controls = new Controls();

    private TestBody(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /** 在 {@code level} 里 {@code (x, y, z)} 处拉起一具。 */
    static TestBody spawn(ServerLevel level, String name, double x, double y, double z) {
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        TestBody body = new TestBody(server, level, profile);
        new ServerGamePacketListenerImpl(server, new Wire(), body, CommonListenerCookie.createInitial(profile, false));
        body.moveTo(x, y, z, 0, 0);
        level.addNewPlayer(body);
        // 测试世界默认创造模式;身体默认生存,要创造的用例自己切
        body.setGameMode(GameType.SURVIVAL);
        return body;
    }

    /** 离开世界。 */
    void leave() {
        serverLevel().removePlayerImmediately(this, RemovalReason.DISCARDED);
    }

    @Override
    public ServerPlayer entity() {
        return this;
    }

    @Override
    public Controls controls() {
        return controls;
    }

    @Override
    public void tick() {
        super.tick();
        Physics.step(this);
    }

    /** 没有对端的连接:下行包全丢。 */
    private static final class Wire extends Connection {

        Wire() {
            super(PacketFlow.SERVERBOUND);
            new EmbeddedChannel(this);
        }

        @Override
        public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}

        /** 本机回环:生态里常有代码把远端地址直接当成 IP 地址用。 */
        @Override
        public java.net.SocketAddress getRemoteAddress() {
            return new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0);
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void tick() {}

        @Override
        public void disconnect(Component message) {}

        @Override
        public void disconnect(DisconnectionDetails details) {}

        @Override
        public void handleDisconnection() {}

        @Override
        public void flushChannel() {}

        @Override
        public void setReadOnly() {}

        @Override
        public void runOnceConnected(java.util.function.Consumer<Connection> action) {}
    }
}
