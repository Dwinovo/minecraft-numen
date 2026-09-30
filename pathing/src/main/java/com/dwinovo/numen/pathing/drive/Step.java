package com.dwinovo.numen.pathing.drive;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.pathing.drive.Blockage.Hitch;
import com.dwinovo.numen.pathing.plan.Breath;
import com.dwinovo.numen.pathing.plan.CostModel;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.Moves;
import com.dwinovo.numen.pathing.plan.Premise;
import com.dwinovo.numen.pathing.plan.Reason;
import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

/**
 * 执行路线上的一步。开始之前在活世界上复核它的前提——与规划时是同一个 {@code Moves.of(kind).premise},同一个方向,
 * 成本模型按此刻的身体与端口现组一份(目标格保护照样并进去);成立就照这一次复核交出的 {@link Maneuver} 去做(世界若已
 * 替它挖开了一格,那一格就不必再挖),不成立就停下,报出是哪一格、什么方块、哪一条前提。执行中由 {@link Watchdog} 看它有没有超期。
 *
 * <h2>憋气</h2>
 * 这一步憋着气时,开始前与执行中的每一刻都按身体此刻的真实氧气把这一段水下剩下的部分重算一遍({@link #breathless}),判据与
 * 规划时同一个({@link Breath#after}、{@link Breath#lasts}):游不到换气的地方就停下,报 {@link Reason#OUT_OF_BREATH}。每一刻都算,
 * 是因为一步在水下被挡住拖长时,期限({@link Watchdog})按这一步的估价给,比氧气宽得多——憋不住的那一刻就得停下,把身体交给
 * 宿主的换气本能,不等期限。
 */
final class Step {

    private final Rig rig;
    private final Maneuver planned;
    private final Maneuver next;
    /** 这一步之后同一段水下接着的几步(都憋着气),按先后;这一步不在水下或后面换得了气为空。 */
    private final List<Maneuver> diving;
    private final Goal goal;
    private final RouteSpec spec;
    private final Watchdog watchdog;
    private Control control;
    /** 这一步与同一段水下接着的几步,按先后;这一步开始时定下,不憋气为空。 */
    private List<Maneuver> dive = List.of();
    /** {@link #dive} 里每一步要几刻,开始时按那一刻的成本模型算好。 */
    private double[] diveTicks = new double[0];
    /** 计划内的坠落开始时身体的血量;不是这样的一步为 NaN。落地时拿它对账。 */
    private float healthBefore = Float.NaN;
    /** 计划内的坠落预计掉几点血。 */
    private int expectedDamage;

    Step(Rig rig, Maneuver planned, Maneuver next, List<Maneuver> diving, Goal goal, RouteSpec spec, Watchdog watchdog) {
        this.rig = rig;
        this.planned = planned;
        this.next = next;
        this.diving = List.copyOf(diving);
        this.goal = goal;
        this.spec = spec;
        this.watchdog = watchdog;
    }

    Maneuver planned() {
        return planned;
    }

    /** 这一步此刻是计划内的坠落。 */
    boolean falls() {
        return control != null && control.falls();
    }

    /** 这一步还有收尾的事没做完,身体落在落点上也先不算走完。 */
    boolean holds() {
        return control != null && control.holds();
    }

    Beat tick() {
        if (control == null) {
            long t0 = System.nanoTime();
            CostModel model = Goal.guarded(goal,
                    CostModel.of(spec, rig.snapshot(), rig.terrain, rig.materials, Threats.NONE));
            Premise premise = recheck(model);
            rig.tally.rechecked(System.nanoTime() - t0);
            if (premise instanceof Premise.Fails fails) {
                return new Beat.Blocked(new Blockage(fails.cell(), rig.world().getBlockState(fails.cell()),
                        planned.kind(), fails.reason(), null));
            }
            Maneuver fresh = ((Premise.Holds) premise).maneuver();
            if (!fresh.to().equals(planned.to())) {
                return new Beat.Blocked(blocked(planned.to(), Hitch.DIVERTED));
            }
            dive(model, fresh);
            Maneuver drowns = breathless(model.body().breath(), 0);
            if (drowns != null) {
                return outOfBreath(drowns, 0);
            }
            control = Control.of(rig, fresh, next);
            double expected = Moves.of(fresh.kind()).cost(model, fresh);
            watchdog.begin(expected, rig.entity.position());
            begun(fresh, expected);
        } else {
            Maneuver drowns = breathless(rig.snapshot().breath(), watchdog.stepTicks());
            if (drowns != null) {
                return outOfBreath(drowns, watchdog.stepTicks());
            }
        }
        Beat beat = control.tick();
        watchdog.observe(rig.entity.position(), beat instanceof Beat.Going going && going.worked());
        if (beat instanceof Beat.Going && watchdog.overran()) {
            PathLog.info("{} 卡住 {} 做了 {} 刻,超过期限 {} 刻;落点 {} 是 {} {}", rig.who, PathLog.step(planned),
                    watchdog.stepTicks(), PathLog.num(watchdog.allowance()), PathLog.pos(planned.to()),
                    PathLog.block(rig.world().getBlockState(planned.to())), PathLog.body(rig.entity));
            return new Beat.Blocked(blocked(planned.to(), Hitch.STUCK));
        }
        return beat;
    }

    /**
     * 一步开始:DEBUG 记走法、估价与期限;落差过一格、会掉血或要倒水接住的坠落是计划内的坠落,记 INFO——落差、落在什么上、
     * 预计掉几点血、接不接水,落地时({@link #finish})再对一次账。
     */
    private void begun(Maneuver m, double expected) {
        if (PathLog.debugging()) {
            PathLog.debug("{} 步 {} 估 {} 刻 期限 {} 刻 改动 {}", rig.who, PathLog.step(m), PathLog.num(expected),
                    PathLog.num(watchdog.allowance()), m.edits().size());
        }
        boolean catches = !m.edits().isEmpty() && m.edits().get(m.edits().size() - 1) instanceof Edit.Catch;
        if (!control.falls() || !(m.drop() > 1 || m.fallDamage() > 0 || catches)) {
            return;
        }
        healthBefore = rig.entity.getHealth();
        expectedDamage = m.fallDamage();
        PathLog.info("{} 计划坠落 {} 落差 {} 落在 {}{} 预计掉 {} 点血 血 {}", rig.who, PathLog.step(m), PathLog.num(m.drop()),
                m.support() != null ? PathLog.block(rig.world().getBlockState(m.support())) : m.landing().kind(),
                catches ? " 上倒的水里" : "", m.fallDamage(), PathLog.num(healthBefore));
    }

    /** 这一步走完,身体落在 {@code node}:计划内的坠落记一行落地——实际掉了几点血。 */
    void finish(BlockPos node) {
        if (Float.isNaN(healthBefore)) {
            return;
        }
        PathLog.info("{} 落地 {} 掉了 {} 点血(预计 {}) {}", rig.who, PathLog.pos(node),
                PathLog.num(healthBefore - rig.entity.getHealth()), expectedDamage, PathLog.body(rig.entity));
    }

    /** 这一步憋着气时,定下这一段水下剩下的几步(这一步与同一段接着的几步)与各要几刻。 */
    private void dive(CostModel model, Maneuver fresh) {
        if (!fresh.submerged()) {
            return;
        }
        List<Maneuver> ahead = new ArrayList<>(diving.size() + 1);
        ahead.add(fresh);
        ahead.addAll(diving);
        dive = List.copyOf(ahead);
        diveTicks = new double[dive.size()];
        for (int i = 0; i < dive.size(); i++) {
            diveTicks[i] = Moves.of(dive.get(i).kind()).ticks(model, dive.get(i));
        }
    }

    /**
     * 此刻的氧气({@code breath})撑不撑得到这一段水下走完:先走完这一步还没用掉的刻数(估价减去已经做了的 {@code elapsed} 刻,
     * 拖过了估价就是零),再依次走过同一段接着的几步;在哪一步走完憋不住,交出那一步,都憋得住(或这一步不憋气)为 null。
     */
    private Maneuver breathless(Breath breath, int elapsed) {
        Breath.Air air = breath.now();
        for (int i = 0; i < dive.size(); i++) {
            double ticks = i == 0 ? Math.max(0, diveTicks[0] - elapsed) : diveTicks[i];
            air = breath.after(air, true, ticks);
            if (!breath.lasts(air)) {
                return dive.get(i);
            }
        }
        return null;
    }

    /**
     * 此刻还在计划内的一段水下:这一步还没开始(开始前按真实氧气判),或已经开始而此刻的氧气撑得到这一段走完。宿主的换气本能
     * 每刻问的就是它({@link Driver#plannedDive}),与 {@link #tick} 每刻判的是同一条。
     */
    boolean holdsBreath() {
        return control == null || breathless(rig.snapshot().breath(), watchdog.stepTicks()) == null;
    }

    /** 憋不住了:记一行,这一步以 {@link Reason#OUT_OF_BREATH} 走不下去。 */
    private Beat outOfBreath(Maneuver drowns, int elapsed) {
        PathLog.info("{} 憋不住气 {} 做了 {} 刻,起这一段水下到 {},此刻的氧气撑不到 {}", rig.who, PathLog.step(planned),
                elapsed, PathLog.pos(drowns.to()), PathLog.body(rig.entity));
        return new Beat.Blocked(new Blockage(drowns.to(), rig.world().getBlockState(drowns.to()), planned.kind(),
                Reason.OUT_OF_BREATH, null));
    }

    /** 同一个前提函数在活世界上再判一次。 */
    private Premise recheck(CostModel model) {
        LiveWorld world = rig.world();
        BlockPos from = planned.from();
        Stance stance = Stance.at(world, model.body().stats(), from);
        if (stance == null) {
            return Premise.fail(from, Reason.NOT_STANDING);
        }
        return Moves.of(planned.kind()).premise(model, world, from, stance, planned.heading());
    }

    private Blockage blocked(BlockPos cell, Hitch hitch) {
        return new Blockage(cell, rig.world().getBlockState(cell), planned.kind(), null, hitch);
    }
}
