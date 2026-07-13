package com.dwinovo.numen.platform;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.platform.services.INetworkChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class ForgeNetworkChannel implements INetworkChannel {

    private static final String PROTOCOL_VERSION = "1";
    private final SimpleChannel channel;
    private int nextId = 0;

    public ForgeNetworkChannel() {
        channel = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(Constants.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals);
    }

    @Override
    public <MSG> void registerClientToServer(
            ResourceLocation id, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, MSG> decoder,
            BiConsumer<MSG, ServerPlayer> handler) {
        channel.registerMessage(nextId++, type, encoder, decoder,
                (msg, ctx) -> {
                    NetworkEvent.Context c = ctx.get();
                    c.enqueueWork(() -> {
                        ServerPlayer sender = c.getSender();
                        if (sender != null) handler.accept(msg, sender);
                    });
                    c.setPacketHandled(true);
                }, Optional.empty());
    }

    @Override
    public void sendToServer(ResourceLocation id, Object payload) {
        channel.sendToServer(payload);
    }

    @Override
    public <MSG> void registerServerToClient(
            ResourceLocation id, Class<MSG> type,
            BiConsumer<MSG, FriendlyByteBuf> encoder,
            Function<FriendlyByteBuf, MSG> decoder,
            Consumer<MSG> handler) {
        channel.registerMessage(nextId++, type, encoder, decoder,
                (msg, ctx) -> {
                    ctx.get().enqueueWork(() -> handler.accept(msg));
                    ctx.get().setPacketHandled(true);
                }, Optional.empty());
    }

    @Override
    public void sendToPlayer(ServerPlayer player, ResourceLocation id, Object payload) {
        channel.send(PacketDistributor.PLAYER.with(() -> player), payload);
    }
}
