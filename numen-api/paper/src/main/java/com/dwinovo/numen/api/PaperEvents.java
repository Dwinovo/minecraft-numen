package com.dwinovo.numen.api;

import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import com.dwinovo.numen.api.brain.ChatRoutes;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.PlayerSessions;
import com.dwinovo.numen.api.platform.ServerLifecycle;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import io.papermc.paper.adventure.PaperAdventure;
import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.entity.Player;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * Paper 的事件接到 Numen API 在 NeoForge / Fabric 上也调的那几个 common 入口(登入、登出、换维度、刻前、起服、重载、聊天)。
 * 停服在插件的 {@code onDisable} 里,指令在 {@link PaperCommands}。
 */
final class PaperEvents implements Listener {

    @EventHandler
    void onJoin(PlayerJoinEvent event) {
        PlayerSessions.joined(handle(event.getPlayer()));
    }

    /**
     * Fabric 客户端进游戏之后才登记频道(NeoForge 在配置阶段就登记了,登入时已经成立):登记到来时他若已在世界里,
     * 就是"能收我们的包了"的那一刻。每个频道的登记都会来一次,谁是我们的、推没推过由 {@code channelReady} 判。
     */
    @EventHandler
    void onChannelRegistered(PlayerRegisterChannelEvent event) {
        PlayerSessions.channelReady(handle(event.getPlayer()));
    }

    @EventHandler
    void onQuit(PlayerQuitEvent event) {
        ServerPlayer player = handle(event.getPlayer());
        if (player instanceof NumenPlayer her) {
            // Numen 自己收她(遣散、休眠、死亡后的收尸)走 CompanionFactory.despawn,那里自己知道;不是它收的,就是被外面请出去的
            // (踢人先到 PlayerKickEvent,已经处理过,入口对第二次调用是空操作)
            if (!CompanionFactory.isLeaving(her)) {
                net.kyori.adventure.text.Component said = event.quitMessage();
                Companions.removedFromOutside(her, said != null ? PaperAdventure.asVanilla(said)
                        : net.minecraft.network.chat.Component.literal(
                                event.getReason().name().toLowerCase(java.util.Locale.ROOT)));
            }
            return;
        }
        PlayerSessions.left(player);
    }

    /** 服主的 {@code /kick}、别的插件踢人:在她被移除之前把理由交给 common。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    void onKick(PlayerKickEvent event) {
        if (handle(event.getPlayer()) instanceof NumenPlayer her && !CompanionFactory.isLeaving(her)) {
            Companions.removedFromOutside(her, PaperAdventure.asVanilla(event.reason()));
        }
    }

    @EventHandler
    void onChangedWorld(PlayerChangedWorldEvent event) {
        PlayerSessions.changedDimension(handle(event.getPlayer()));
    }

    /**
     * 排程机器的心跳。刻前事件在实体更新之前,与模组上的 Pre / START 同一个位置:任务落下的移动与按键输入由本刻的实体物理立即消费。
     */
    @EventHandler
    void onTickStart(ServerTickStartEvent event) {
        CompanionTickDispatcher.tick(MinecraftServer.getServer());
    }

    /** 服务器起好(世界已加载)。{@code /reload} 也会发这个事件,那一次不算起服。 */
    @EventHandler
    void onLoaded(ServerLoadEvent event) {
        if (event.getType() == ServerLoadEvent.LoadType.STARTUP) {
            ServerLifecycle.fireStarted(MinecraftServer.getServer());
        }
    }

    /** 数据重载完成({@code /reload} 或插件触发的重载)。 */
    @EventHandler
    void onResourcesReloaded(ServerResourcesReloadedEvent event) {
        ServerLifecycle.fireReloaded();
    }

    /**
     * 聊天事件在异步线程上,取消与否要当场答:这句归不归 Numen是只读判断({@link ChatRoutes#routed}),当场取消;
     * 接下来的处理(进会话、可能回话)交回主线程。取消排在最前,别的聊天插件见到的是已取消的事件,这句话不会漏到公屏上。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    void onChat(AsyncChatEvent event) {
        ServerPlayer player = handle(event.getPlayer());
        if (!ChatRoutes.routed(player.getUUID())) {
            return;
        }
        event.setCancelled(true);
        String text = event.signedMessage().message();
        player.server.execute(() -> ChatRoutes.deliver(player, text));
    }

    private static ServerPlayer handle(Player player) {
        return ((CraftPlayer) player).getHandle();
    }
}
