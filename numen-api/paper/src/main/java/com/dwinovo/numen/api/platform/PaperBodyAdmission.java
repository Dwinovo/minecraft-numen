package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.entity.FakeConnection;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.platform.services.IBodyAdmission;
import com.mojang.authlib.GameProfile;
import io.papermc.paper.adventure.PaperAdventure;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.bukkit.Bukkit;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetAddress;
import java.util.function.Consumer;

/**
 * Paper 上她加入的规矩——她是个真玩家,所以照真玩家的顺序来:
 * <ul>
 *   <li><b>接纳</b>:{@code placeNewPlayer} 不发 {@code AsyncPlayerPreLoginEvent} 与 {@code PlayerLoginEvent},LuckPerms 这类插件
 *       靠它们装载一个玩家(权限数据、封禁、登录保护)。所以加入前补发这两个:前一个必须在异步线程上发(登录插件会在里头读库),
 *       后一个回到主线程发。任何一个被拒,她就进不来,原因照插件给的说给玩家听。</li>
 *   <li><b>加入之后</b>:她不是区块的观察者(Moonrise 把玩家当观察者,给她加载 9×9 并编区块包,她用不着,
 *       有界加载由 {@code CompanionChunkLoader} 的区域票管);她的权限等于主人的。</li>
 *   <li><b>下行包</b>:她这条连接收到的每个包交给 {@code FakeClient}(传送回执、对她说的话)。</li>
 * </ul>
 * 不打 {@code NPC} 元数据:她就是个玩家,别的插件把她当玩家处理。
 */
public final class PaperBodyAdmission implements IBodyAdmission {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();

    @Override
    public void admit(NumenPlayer body, FakeConnection connection, Consumer<Component> verdict) {
        connection.deliverOutboundTo(body.fakeClient()::onOutbound);
        MinecraftServer server = body.server;
        GameProfile profile = body.getGameProfile();
        JavaPlugin plugin = JavaPlugin.getProvidingPlugin(PaperBodyAdmission.class);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 异步线程上的任何异常都要让这一趟以失败收场:任务死在这里,"在路上"的登记就永远放不掉。原因进日志,也作为拒绝的原因交回
            Component refusal;
            try {
                AsyncPlayerPreLoginEvent pre = new AsyncPlayerPreLoginEvent(profile.getName(), LOOPBACK, profile.getId());
                Bukkit.getPluginManager().callEvent(pre);
                refusal = pre.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED ? null
                        : PaperAdventure.asVanilla(pre.kickMessage());
            } catch (Throwable failure) {
                refusal = failed(profile.getName(), failure);
            }
            Component early = refusal;
            server.execute(() -> verdict.accept(early != null ? early : login(body)));
        });
    }

    /** 回到主线程补发 {@code PlayerLoginEvent};出了异常同样以失败收场。 */
    private static Component login(NumenPlayer body) {
        try {
            PlayerLoginEvent login = new PlayerLoginEvent(body.getBukkitEntity(), "localhost", LOOPBACK);
            Bukkit.getPluginManager().callEvent(login);
            return login.getResult() == PlayerLoginEvent.Result.ALLOWED ? null : PaperAdventure.asVanilla(login.kickMessage());
        } catch (Throwable failure) {
            return failed(body.getGameProfile().getName(), failure);
        }
    }

    private static Component failed(String name, Throwable failure) {
        Constants.LOG.error("[numen] 接纳 {} 时出错", name, failure);
        return Component.literal("接纳时出错:" + failure);
    }

    @Override
    public void joined(NumenPlayer body) {
        exitPlayerChunkLoading(body);
        OwnerPermissible.injectInto(body.getBukkitEntity(), body.getOwnerUuid());
    }

    /**
     * 从 Moonrise 的玩家区块加载里摘掉:{@code removePlayer} 自己先查旗标,所以必须先摘再改旗标。之后换维度、重生走的
     * 加入路径都先查这个旗标,不会把她加回去(重新加入时旗标被置回真,这里再摘一次)。
     */
    private static void exitPlayerChunkLoading(NumenPlayer body) {
        ((ServerLevel) body.level()).moonrise$getPlayerChunkLoader().removePlayer(body);
        body.moonrise$setRealPlayer(false);
    }
}
