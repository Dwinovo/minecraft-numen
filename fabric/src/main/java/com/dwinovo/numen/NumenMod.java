package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;

public class NumenMod implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, env) ->
                        com.dwinovo.numen.entity.NumenCommands.register(dispatcher));

        // When an owner logs in, bring their dormant companions back.
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    ServerPlayer player = handler.getPlayer();
                    if (player instanceof com.dwinovo.numen.entity.NumenPlayer) return;  // not the companion itself
                    com.dwinovo.numen.entity.Companions.respawnAllOwnedBy(server, player.getUUID());
                    com.dwinovo.numen.entity.Companions.syncRosterToOwner(server, player);
                });

        // The companion crossed a portal on its own — tell its brain (ambient world event).
        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> {
                    if (player instanceof com.dwinovo.numen.entity.NumenPlayer ap) {
                        com.dwinovo.numen.entity.Companions.onDimensionChanged(ap);
                    }
                });

        // Advance budget-sliced long-range block scans.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.numen.task.tasks.ScanBlocksJob::tick);
        // Drive companion player-body tasks (move_to / auto_mine) each tick.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.numen.task.CompanionTickDispatcher::tick);
        // Snapshot loaded chunks near companions each tick, for the off-thread planner to read live.
        ServerTickEvents.END_SERVER_TICK.register(
                com.dwinovo.numen.pathing.cache.PathCaches::serverTick);
        // Release those chunk references when the server stops (don't pin an old world's chunks).
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> com.dwinovo.numen.pathing.cache.PathCaches.dropAll());

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Fabric.");
    }
}
