package com.dwinovo.numen.pathing.search;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Stance;

import net.minecraft.core.BlockPos;

/**
 * 一条路线:起点与一串前提成立的步子,每步带着它的代价与走完时憋气的样子。路线是推导出来的东西,不是可交接的——能交接的是目标加规格,
 * 路线由搜索随时重算。
 *
 * @param start       起点节点
 * @param startStance 身体在起点上怎么待着
 * @param legs        一步一步,按先后
 */
public record Route(BlockPos start, Stance startStance, List<Leg> legs) {

    /**
     * 一步:这一步的事实、它按成本模型算出的代价,与走完这一步时身体憋气的样子(搜索沿路推算的,{@link Breath#after})。
     */
    public record Leg(Maneuver maneuver, double cost, Breath.Air air) {}

    /**
     * 路线上连着几步眼睛都换不了气的一段水下({@link Maneuver#submerged}):从哪一步的起点下去、到哪一步的落点,几步,
     * 憋完这一段时憋气的样子。
     */
    public record Dive(BlockPos from, BlockPos to, int steps, Breath.Air end) {

        /** 一口气憋了几刻。 */
        public double held() {
            return end.held();
        }

        /** 憋完还能再撑几刻(撑到氧气见底)。 */
        public double left() {
            return end.left();
        }
    }

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

    /** 同样的步子按另一份成本模型重新定价(步子本身的前提不变,只是价钱的出处换了;憋气只看身体,照旧)。 */
    public Route repriced(CostModel model) {
        List<Leg> out = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            Maneuver m = leg.maneuver();
            out.add(new Leg(m, Moves.of(m.kind()).cost(model, m), leg.air()));
        }
        return new Route(start, startStance, out);
    }

    /**
     * 同样的步子,憋气从 {@code air} 起按 {@code model} 的身体一步步重算({@link Breath#after},每步的刻数是那种走法的
     * {@link com.dwinovo.numen.pathing.plan.Move#ticks});憋不住的步子照样留着,憋气扣到见底以下。诊断拿它看一条不计憋气
     * 搜出来的路在哪一段憋不住。
     */
    public Route breathed(CostModel model, Breath.Air air) {
        Breath breath = model.body().breath();
        List<Leg> out = new ArrayList<>(legs.size());
        for (Leg leg : legs) {
            Maneuver m = leg.maneuver();
            air = breath.after(air, m.submerged(), Moves.of(m.kind()).ticks(model, m));
            out.add(new Leg(m, leg.cost(), air));
        }
        return new Route(start, startStance, out);
    }

    /** 路线上的每一段水下,按先后。 */
    public List<Dive> dives() {
        List<Dive> out = new ArrayList<>();
        int first = -1;
        for (int i = 0; i <= legs.size(); i++) {
            boolean under = i < legs.size() && legs.get(i).maneuver().submerged();
            if (under && first < 0) {
                first = i;
            } else if (!under && first >= 0) {
                Leg last = legs.get(i - 1);
                out.add(new Dive(legs.get(first).maneuver().from(), last.maneuver().to(), i - first, last.air()));
                first = -1;
            }
        }
        return out;
    }
}
