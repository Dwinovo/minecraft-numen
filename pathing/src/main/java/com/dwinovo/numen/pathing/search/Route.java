package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Stance;

import net.minecraft.core.BlockPos;

/**
 * 一条路线:起点与一串前提成立的步子,每步带着它的代价。路线是推导出来的东西,不是可交接的——能交接的是目标加规格,
 * 路线由搜索随时重算。
 *
 * @param start       起点节点
 * @param startStance 身体在起点上怎么待着
 * @param legs        一步一步,按先后
 */
public record Route(BlockPos start, Stance startStance, List<Leg> legs) {

    /** 一步:这一步的事实与它按成本模型算出的代价。 */
    public record Leg(Maneuver maneuver, double cost) {}

    public Route {
        legs = List.copyOf(legs);
    }

    /** 终点节点。 */
    public BlockPos end() {
        return legs.isEmpty() ? start : legs.get(legs.size() - 1).maneuver().to();
    }

    /** 身体在终点上怎么待着。 */
    public Stance endStance() {
        return legs.isEmpty() ? startStance : legs.get(legs.size() - 1).maneuver().landing();
    }

    public double cost() {
        double sum = 0;
        for (Leg leg : legs) {
            sum += leg.cost();
        }
        return sum;
    }

    /** 路线经过的节点,含起点。 */
    public List<BlockPos> nodes() {
        List<BlockPos> out = new ArrayList<>(legs.size() + 1);
        out.add(start);
        for (Leg leg : legs) {
            out.add(leg.maneuver().to());
        }
        return out;
    }

    /** 路上要做的全部改动,按先后。 */
    public List<Edit> edits() {
        List<Edit> out = new ArrayList<>();
        for (Leg leg : legs) {
            out.addAll(leg.maneuver().edits());
        }
        return out;
    }

    /** 改地形的格数(挖加放)。 */
    public int alterations() {
        int n = 0;
        for (Leg leg : legs) {
            n += leg.maneuver().alterations();
        }
        return n;
    }

    /** 同样的步子按另一份成本模型重新定价(步子本身的前提不变,只是价钱的出处换了)。 */
    public Route repriced(CostModel model) {
        List<Leg> out = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            Maneuver m = leg.maneuver();
            out.add(new Leg(m, Moves.of(m.kind()).cost(model, m)));
        }
        return new Route(start, startStance, out);
    }
}
