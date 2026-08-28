package com.dwinovo.numen.plugins.ysm;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Set;
import java.util.UUID;

/**
 * 权限跟着主人走。<b>只有这一件事</b>——外观是同伴自己的。
 *
 * <h2>为什么做成对账而不是事件</h2>
 * 主人什么时候被授权了新模型,没有事件可订阅。对账每隔几秒读一次主人的授权表、
 * 跟同伴的比一比,不一致就补上——主人刚拿到的、之前漏掉的、重启前后的,同一条逻辑
 * 全覆盖。脉冲可以丢,电平不会骗。
 *
 * <h2>为什么不继承主人的模型</h2>
 * 同伴有自己的皮肤(引擎的皮肤库,主人亲手给她挑的)。出场时套上主人那身 YSM 模型
 * 等于把那个选择<b>覆盖掉</b>——而且只覆盖一次,判据藏在一个状态文件里,删掉文件
 * 行为就变,谁也看不出为什么。
 *
 * <p>所以她进来就是她自己,想换再换,随时换回来。车万女仆插件那边同理——两个外观
 * 插件一个规矩,不必记谁会继承谁不会。
 */
public final class OwnerSync {

    /** 对账间隔。权限变化是人手动操作的,几秒的延迟没人感觉得到。 */
    private static final int EVERY_TICKS = 20 * 5;

    private static int counter;

    private OwnerSync() {}

    /**
     * 同伴刚进世界。对账每隔几秒本来也会跑到,但出场那一下要立刻对齐——
     * 否则她带着上一次的授权露面几秒钟。
     */
    public static void onSpawn(NumenPlayer companion) {
        MinecraftServer server = companion.getServer();
        if (server != null) reconcile(server, companion);
    }

    /** 每 tick 调用,内部自己限频。 */
    public static void tick(MinecraftServer server) {
        if (++counter < EVERY_TICKS) return;
        counter = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p instanceof NumenPlayer companion) reconcile(server, companion);
        }
    }

    private static void reconcile(MinecraftServer server, NumenPlayer companion) {
        UUID ownerUuid = companion.getOwnerUuid();
        if (ownerUuid == null) return;
        ServerPlayer owner = server.getPlayerList().getPlayer(ownerUuid);
        if (owner == null) return;                 // 主人不在线,没有可跟随的状态

        Set<String> want = Ysm.readAuthorized(owner);
        Set<String> have = Ysm.readAuthorized(companion);
        if (want.equals(have)) return;

        String me = companion.getGameProfile().getName();
        Ysm.authClear(server, me);
        for (String model : want) Ysm.authAdd(server, me, model);
    }
}
