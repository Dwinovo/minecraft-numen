package com.dwinovo.numen.api;

import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.platform.PlayerSessions;
import net.fabricmc.api.ModInitializer;

public class NumenMod implements ModInitializer {

    @Override
    public void onInitialize() {
        NumenNetwork.register();

        // Dev: /numen_summon — create a companion fake player at the caller.
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registry, env) ->
                        com.dwinovo.numen.api.entity.NumenCommands.register(dispatcher));

        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> PlayerSessions.joined(handler.getPlayer()));
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> PlayerSessions.left(handler.getPlayer()));

        // 聊天栏切进了会话的玩家,他打的字进会话、不广播到公屏(见 ChatRoutes)
        net.fabricmc.fabric.api.message.v1.ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
                (message, sender, params) -> !com.dwinovo.numen.api.brain.ChatRoutes.take(sender,
                        message.signedContent()));

        net.fabricmc.fabric.api.entity.event.v1.ServerEntityWorldChangeEvents.AFTER_PLAYER_CHANGE_WORLD.register(
                (player, origin, destination) -> PlayerSessions.changedDimension(player));

        // 排程机器的心跳:每 tick 驱动全部同伴的竞价/任务/收尾。
        // 挂 START(实体更新之前):任务→导航→执行器落下的移动/按键输入由
        // 本 tick 的实体物理立即消费——"在位置 P 做的决策作用于从 P 出发
        // 的这一步",不产生一 tick 的输入滞后(潜行/松跳等边缘时机全靠它)。
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.START_SERVER_TICK.register(
                com.dwinovo.numen.api.task.CompanionTickDispatcher::tick);

        // 服务器停了：属于那个世界的进程内状态一起作废。单人「退出存档」不结束进程，
        // 静态表会原封不动活到下一个存档——谁持有谁在自己那边报到，见 ServerLifecycle。
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED.register(
                com.dwinovo.numen.api.platform.ServerLifecycle::fireStarted);
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                server -> com.dwinovo.numen.api.platform.ServerLifecycle.fireStopped());
        // /reload:磁盘上手改的库、钥匙串、设置重新读
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.END_DATA_PACK_RELOAD.register(
                (server, resources, success) -> com.dwinovo.numen.api.platform.ServerLifecycle.fireReloaded());

        CommonClass.init();
        Constants.LOG.info("Numen mod initialised on Fabric.");
    }
}
