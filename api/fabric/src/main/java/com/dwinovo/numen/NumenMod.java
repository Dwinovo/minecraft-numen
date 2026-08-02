package com.dwinovo.numen;

import com.dwinovo.numen.network.NumenNetwork;
import net.fabricmc.api.ModInitializer;
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

        // 排程机器的心跳:每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 START(实体更新之前):任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——"在位置 P 做的决策作用于从 P 出发
        // 的这一步",不产生一 tick 的输入滞后(潜行/松跳等边缘时机全靠它)。
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(
                com.dwinovo.numen.task.CompanionTickDispatcher::tick);

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Fabric.");
    }
}
