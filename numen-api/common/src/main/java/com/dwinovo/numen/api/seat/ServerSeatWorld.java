package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Venue;
import com.dwinovo.numen.api.brain.Voice;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.program.ClientTransport;
import com.dwinovo.numen.api.program.LocalPrograms;
import com.dwinovo.numen.api.program.ProgramHost;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.Executor;

/** 产品里的 {@link SeatWorld}:正在跑的那个 Minecraft 服务端。服务器起来时接上,停了放掉。 */
final class ServerSeatWorld implements SeatWorld {

    private volatile MinecraftServer server;
    private volatile LocalPrograms programs;

    void start(MinecraftServer server, ClientTransport transport) {
        this.programs = new LocalPrograms(server, transport);
        this.server = server;
    }

    void stop() {
        this.server = null;
        this.programs = null;
    }

    private MinecraftServer running() {
        MinecraftServer running = server;
        if (running == null) {
            throw new IllegalStateException("no server is running");
        }
        return running;
    }

    @Override
    public Executor main() {
        return task -> running().execute(task);
    }

    @Override
    public boolean mayOpen(UUID player, UUID companion) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(running()).find(companion);
        return entry != null && entry.owner().equals(player) && Presence.hasClient(player);
    }

    @Override
    public String state(UUID companion) {
        return CompanionLoops.get(companion).map(CompanionLoop::runtimeState).orElse("");
    }

    @Override
    public long gameTime() {
        return running().overworld().getGameTime();
    }

    @Override
    public long dayTime() {
        return running().overworld().getDayTime();
    }

    @Override
    public ProgramHost programs() {
        return programs;
    }

    @Override
    public void say(UUID companion, String text) {
        CompanionRegistry.Entry entry = CompanionRegistry.get(running()).find(companion);
        ServerPlayer owner = entry == null ? null : running().getPlayerList().getPlayer(entry.owner());
        if (owner != null) {
            owner.sendSystemMessage(Voice.line(Venue.PANEL, entry.name(), null, text));
        }
    }

    @Override
    public JsonObject catalog(UUID companion) {
        return SeatCatalog.of(companion);
    }
}
