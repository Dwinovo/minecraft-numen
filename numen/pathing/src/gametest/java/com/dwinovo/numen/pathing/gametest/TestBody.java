package com.dwinovo.numen.pathing.gametest;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import com.dwinovo.numen.pathing.body.Body;
import com.dwinovo.numen.api.entity.Controls;
import com.dwinovo.numen.api.entity.Hotbar;
import com.dwinovo.numen.api.entity.Look;
import com.dwinovo.numen.api.entity.Mouse;
import com.dwinovo.numen.api.entity.Physics;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.StringUtil;
import net.minecraft.world.level.GameType;

/**
 * 夹具自己的假玩家,不是同伴:一具普通的服务端玩家,带一副键盘({@link Controls})、视角、鼠标与快捷栏(同伴用的那几件,不过权限层),每刻在自己的实体刻里跑模块的物理步进
 * ({@link Physics#step}),别的什么也不做。它能走通就说明寻路模块不依赖宿主。
 *
 * <p>它只进世界、不进玩家列表(不"登录"):没有客户端,也就没有与服务器协商过任何模组的网络通道,登录时别的模组发来的
 * 自定义包会被网络层当场拒掉。进了世界就有区块跟着它加载、实体刻照常跑;连接丢掉一切下行包。
 *
 * <p>客户端只画认得的玩家:生成一具玩家实体之前,原版客户端要先收到这个玩家的档案({@code ClientPacketListener.handleAddEntity}
 * 收到没见过档案的玩家就丢掉)。开发客户端里 {@code /test run} 现场围观时,进世界前把档案发给在线的人(只加档案、不进 Tab 列表),
 * 离开时撤掉;无头跑批没有在线的人,发了也没人收。
 */
final class TestBody extends ServerPlayer implements Body {

    private final Controls controls = new Controls();
    private final Look look = new Look(this);
    private Mouse mouse = new Mouse(this);
    private final Hotbar hotbar = new Hotbar(this);
    /** 受到过的伤害合计(含出生无敌期间被挡掉的):用例据此看一路上有没有掉血,不必等无敌过去。 */
    private float incoming;
    private Wire wire;

    private TestBody(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    /** 在 {@code level} 里 {@code (x, y, z)} 处拉起一具。{@code name} 是玩家档案的名字,照原版的规矩(至多 16 个字符):它要随档案发给客户端。 */
    static TestBody spawn(ServerLevel level, String name, double x, double y, double z) {
        if (!StringUtil.isValidPlayerName(name)) {
            throw new IllegalArgumentException("not a valid player name (at most 16 characters): " + name);
        }
        MinecraftServer server = level.getServer();
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        TestBody body = new TestBody(server, level, profile);
        body.wire = new Wire();
        ServerGamePacketListenerImpl listener = new ServerGamePacketListenerImpl(server, body.wire, body, CommonListenerCookie.createInitial(profile, false));
        // 连接认得这个监听器(原版放玩家进来时也这么设):断开时才会通知它,挂在连接上的东西才跟着收场
        body.wire.setupInboundProtocol(
                GameProtocols.SERVERBOUND_TEMPLATE.bind(RegistryFriendlyByteBuf.decorator(server.registryAccess())), listener);
        body.moveTo(x, y, z, 0, 0);
        server.getPlayerList().broadcastAll(
                new ClientboundPlayerInfoUpdatePacket(EnumSet.of(ClientboundPlayerInfoUpdatePacket.Action.ADD_PLAYER), List.of(body)));
        level.addNewPlayer(body);
        // 测试世界默认创造模式;身体默认生存,要创造的用例自己切
        body.setGameMode(GameType.SURVIVAL);
        return body;
    }

    /**
     * 离开世界。走连接的收场:先关掉通道,再让连接做它的断开处理({@code handleDisconnection},里面调监听器的 {@code onDisconnect},
     * 再走 {@link net.minecraft.server.players.PlayerList#remove}),好让挂在连接上的东西各自收场——Fabric 的网络模块按连接登记了
     * 一份收发状态,只有这一步才撤;原版给触发器监听也只在 {@code remove} 里撤。
     *
     * <p>还有两张按 UUID 登记的表:建身体时玩家列表给它记了一份进度账({@code PlayerAdvancements},逐条进度的状态,每具上千条)
     * 和一份统计。身体没有"登录"过,{@code remove} 认的是登录表里的那一条,对这两张表不动手;矩阵一批要拉起上千具身体,
     * 不清就整个留在堆里,几 G 的堆也撑爆。玩家列表没有公开的撤法,这里按字段名清掉(夹具只在开发环境里跑,字段是原版名)。
     */
    void leave() {
        wire.hangUp();
        for (String table : List.of("advancements", "stats")) {
            try {
                java.lang.reflect.Field field = net.minecraft.server.players.PlayerList.class.getDeclaredField(table);
                field.setAccessible(true);
                ((java.util.Map<?, ?>) field.get(server.getPlayerList())).remove(getUUID());
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("玩家列表里没有 " + table + " 这张表,夹具要跟着改", e);
            }
        }
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
    public Look look() {
        return look;
    }

    @Override
    public Mouse mouse() {
        return mouse;
    }

    /** 换一个鼠标:用例派生一个 {@link Mouse},包在原版的鼠标外面看它做了什么、或在它动手那一刻动世界。 */
    void mouse(Mouse watching) {
        this.mouse = watching;
    }

    @Override
    public Hotbar hotbar() {
        return hotbar;
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        incoming += amount;
        return super.hurt(source, amount);
    }

    /** 到此刻为止受到的伤害合计。 */
    float incoming() {
        return incoming;
    }

    @Override
    public void tick() {
        super.tick();
        Physics.step(this, controls);
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

        /** 真的断开一次:平时的断开都是空操作(没有对端可通知),离开世界时才走原版的断开与收场。 */
        void hangUp() {
            super.disconnect(new DisconnectionDetails(Component.empty()));
            super.handleDisconnection();
        }

        @Override
        public void flushChannel() {}

        @Override
        public void setReadOnly() {}

        @Override
        public void runOnceConnected(java.util.function.Consumer<Connection> action) {}
    }
}
