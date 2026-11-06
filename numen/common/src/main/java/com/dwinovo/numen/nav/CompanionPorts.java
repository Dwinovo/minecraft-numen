package com.dwinovo.numen.nav;

import java.util.List;

import com.dwinovo.numen.combat.Menace;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.api.Snapshots;
import com.dwinovo.numen.pathing.api.Trace;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.api.permission.Permission;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.world.item.Item;

/**
 * 同伴接入寻路的端口,只在这里组:她的身体(键盘、视角、鼠标、快捷栏都在它上面)、此刻的权限快照({@link GateTerrain})、这一趟愿意
 * 垫的料({@link ThrowawayBlocks})、附近的敌对生物({@link Menace#dangers}),搜索看这台服务器定的表({@link NavClock})。开一趟路、只搜不走地规划、给一格估挖掘的价钱,
 * 用的都是这一套。在世界所在的线程上调:权限快照与垫路料在这一刻取。
 */
public final class CompanionPorts {

    /** 她附近多远的敌对生物算进要避开的。 */
    private static final double DANGER_SCAN = 16.0;

    private CompanionPorts() {}

    /** 她附近此刻要避开的敌对生物(每次派发搜索时问一次)。 */
    public static Threats dangers(NumenPlayer player) {
        return () -> Menace.dangers(player, DANGER_SCAN);
    }

    /** 她的寻路门面,避开 {@code threats},垫路从 {@code materials} 里挑。 */
    public static Navigator navigator(NumenPlayer player, Threats threats, List<Item> materials) {
        return Navigator.of(new CompanionBody(player), new Ports(terrain(player), materials(player, materials),
                threats, NavClock.of(player.server), pathTrail(player)));
    }

    /**
     * 她的旁路轨迹,接成寻路的 {@link Trace}:一趟怎么收的场、卡在哪一格、按原因分的重搜次数,与她每一轮看到的、
     * 每一刻在哪写在同一个文件里。门面那一侧只给值,落盘的形状在这里定。
     */
    private static Trace pathTrail(NumenPlayer player) {
        return event -> com.dwinovo.numen.api.agent.trace.TraceRecorders.of(player.getUUID())
                .ifPresent(trace -> trace.event("path", pathBody(event)));
    }

    /** 一趟收场写成轨迹里的一行:坐标写成数组,结局与原因照它们给的类别名,重搜次数按原因各记一项。 */
    private static JsonObject pathBody(Trace.PathEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("outcome", event.outcome());
        put(o, "at", event.at());
        if (event.cell() != null) {
            put(o, "cell", net.minecraft.world.phys.Vec3.atCenterOf(event.cell()));
        }
        if (event.reason() != null) {
            o.addProperty("reason", event.reason());
        }
        o.addProperty("ticks", event.ticks());
        o.addProperty("steps", event.steps());
        if (!event.replans().isEmpty()) {
            JsonObject replans = new JsonObject();
            event.replans().forEach(replans::addProperty);
            o.add("replans", replans);
        }
        return o;
    }

    private static void put(JsonObject o, String key, net.minecraft.world.phys.Vec3 v) {
        if (v == null) {
            return;
        }
        JsonArray a = new JsonArray();
        a.add(v.x);
        a.add(v.y);
        a.add(v.z);
        o.add(key, a);
    }

    /** 按 {@code spec} 与她此刻的身体、端口组一份成本模型:估一格挖多久、许不许挖,与寻路用的是同一份定价。 */
    static CostModel model(NumenPlayer player, RouteSpec spec) {
        return CostModel.of(spec, Snapshots.of(player), terrain(player), materials(player, ThrowawayBlocks.factory()),
                dangers(player));
    }

    private static TerrainPolicy terrain(NumenPlayer player) {
        return new GateTerrain(Permission.gateFor(player));
    }

    private static Materials materials(NumenPlayer player, List<Item> materials) {
        return () -> ThrowawayBlocks.next(player, materials);
    }
}
