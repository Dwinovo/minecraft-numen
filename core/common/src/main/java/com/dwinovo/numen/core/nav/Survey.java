package com.dwinovo.numen.core.nav;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Outcome;
import com.dwinovo.numen.pathing.api.PlanQuery;
import com.dwinovo.numen.pathing.api.PlanResult;
import com.dwinovo.numen.pathing.api.Planning;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.world.item.Item;

/**
 * 规划:只搜不走,不占身体、不碰世界。一串路段从她脚下起逐段规划,每段一次搜索(一次快照看得清的地方),下一段接在上一段的
 * 终点后面({@link PlanQuery#after});哪一段没走到就停在那一段,后面的不规划——它们从哪儿起还不知道。
 *
 * <p>执行是另一件事({@link Trip}):照一段规划出的路走,路上边走边细算。{@code numen.route.plan}、{@code numen.move.go} 从别处出发时的重新规划、
 * 跟随受理前的那一次,规划都是这一处。
 */
public final class Survey {

    /** 一段:去哪、按什么规格。 */
    public record Leg(Goal goal, RouteSpec spec) {}

    /**
     * 一段规划出来的样子。
     *
     * @param route   走得到时的整段路;没走到为 null
     * @param partial 没走到时朝目标推进、看清了的那一截(预算用完、伸出了快照之类);没有为 null
     * @param outcome 没走到时的结局;走到了为 null
     */
    public record Found(Route route, Route partial, Outcome outcome) {

        public boolean reached() {
            return route != null;
        }
    }

    private final Navigator navigator;
    private final List<Leg> legs;
    private final List<Found> found = new ArrayList<>();
    private Planning current;
    private boolean done;

    private Survey(Navigator navigator, List<Leg> legs) {
        this.navigator = navigator;
        this.legs = List.copyOf(legs);
        if (this.legs.isEmpty()) {
            throw new IllegalArgumentException("规划至少要有一段");
        }
    }

    /**
     * 从她脚下起规划这几段,避开她附近此刻的敌对生物,垫路从 {@code materials} 里挑。在世界所在的线程上调:权限快照与垫路料在这一刻取。
     */
    public static Survey of(NumenPlayer player, List<Leg> legs, List<Item> materials) {
        return of(CompanionPorts.navigator(player, CompanionPorts.dangers(player), materials), legs);
    }

    /** 用这一副门面规划这几段。 */
    public static Survey of(Navigator navigator, List<Leg> legs) {
        return new Survey(navigator, legs);
    }

    /**
     * 有结论了就交出每一段规划出来的样子(按顺序;没走到的那一段是最后一个,后面的没有规划),还没有为 null。每刻调一次:
     * 一段的结论回来就派下一段。
     */
    public List<Found> poll() {
        if (done) {
            return List.copyOf(found);
        }
        if (current == null) {
            current = navigator.plan(PlanQuery.of(legs.get(0).goal(), legs.get(0).spec(), 1));
        }
        PlanResult result = current.poll();
        if (result == null) {
            return null;
        }
        current = null;
        if (result.candidates().isEmpty()) {
            found.add(new Found(null, result.partial() == null ? null : result.partial().route(), result.outcome()));
            done = true;
            return List.copyOf(found);
        }
        Route route = result.candidates().get(0).route();
        found.add(new Found(route, null, null));
        if (found.size() == legs.size()) {
            done = true;
            return List.copyOf(found);
        }
        Leg next = legs.get(found.size());
        current = navigator.plan(PlanQuery.of(next.goal(), next.spec(), 1).after(route));
        return null;
    }

    /** 不要了:在飞的搜索作废。 */
    public void cancel() {
        if (current != null) {
            current.cancel();
            current = null;
        }
        done = true;
    }
}
