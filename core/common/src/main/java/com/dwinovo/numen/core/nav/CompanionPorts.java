package com.dwinovo.numen.core.nav;

import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.permission.Permission;

/**
 * 同伴接入寻路的端口,只在这里组:她的身体、她的手({@link CompanionHands})、此刻的权限快照({@link GateTerrain})、她自己的
 * 垫路料清单({@link ThrowawayBlocks})、附近的敌对生物({@link Menace#dangers})。开一趟路、只搜不走地规划、给一格估挖掘的价钱,
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

    /** 她的寻路门面,避开 {@code threats}。 */
    public static Navigator navigator(NumenPlayer player, Threats threats) {
        return Navigator.of(player, new Ports(CompanionHands.of(player), terrain(player), materials(player),
                threats));
    }

    /** 按 {@code spec} 与她此刻的身体、端口组一份成本模型:估一格挖多久、许不许挖,与寻路用的是同一份定价。 */
    static CostModel model(NumenPlayer player, RouteSpec spec) {
        return CostModel.of(spec, Snapshots.of(player), terrain(player), materials(player), dangers(player));
    }

    private static TerrainPolicy terrain(NumenPlayer player) {
        return new GateTerrain(Permission.gateFor(player));
    }

    private static Materials materials(NumenPlayer player) {
        return () -> ThrowawayBlocks.next(player);
    }
}
