package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.core.nav.RouteQueries;
import com.dwinovo.numen.core.route.Description;
import com.dwinovo.numen.core.route.Plan;
import com.dwinovo.numen.core.route.Planning;
import com.dwinovo.numen.core.route.Plans;
import com.dwinovo.numen.core.route.RouteText;
import com.dwinovo.numen.core.route.Stop;
import com.dwinovo.numen.core.route.Target;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

/**
 * {@code numen.route}:寻路——照一份描述只搜不走地规划一趟路。描述是一张表(去处、途经点、移动方式、偏好旋钮、避开、垫路料,见
 * {@link Description.Directions}),不是名词:不存、不起名,常走的路她记在札记里或写成模块。计划交回程序({@link RouteText.Plan}),
 * {@code numen.move.go} 照它走;走不通不是错,计划的 {@code ok} 是 false、{@code why} 说为什么,她改描述再规划。只有描述本身写错才抛错。
 */
public final class RouteApi {

    private RouteApi() {}

    /** 描述里的几种值先登记,{@code numen.move.go} 收的计划也在这里登记。 */
    public static void install(NumenApi numen) {
        numen.codec(Target.class, Target.CODEC);
        numen.codec(Stop.class, Stop.CODEC);
        numen.codec(Description.Costs.class, Description.Costs.CODEC);
        numen.codec(Description.Places.class, Description.Places.CODEC);
        numen.codec(Plans.Ref.class, Plans.Ref.CODEC);
        numen.api("route", "Pathfinding: plan a walk from a description, without moving; numen.move.go walks the plan.",
                RouteApi.class);
    }

    /** 规划:从她脚下只搜不走,不占身体。结论出来那一刻交回;去处此刻就编不成、驾船的一趟当场交回。 */
    @Fn("Plan a walk from where you stand, without moving: each leg's way, the blocks it would break or place, and the "
            + "cells it would ask your owner about.")
    @Example("local plan = numen.route.plan({to = {x = 120, y = 64, z = -35}})\nprint(plan.ok, plan.steps, plan.seconds)")
    @Example("local plan = numen.route.plan({to = ore, arrive = \"dig\", costs = {dig = true, place = true}})\n"
            + "if not plan.ok then print(plan.why) end")
    @Example("numen.route.plan({to = home, stops = {{to = {x = 10, y = 70, z = 5}}}, avoid = {\"water\"}})")
    @Example("numen.route.plan({to = creeper, arrive = \"away\", range = 12})")
    @Note("Read-only and does not take the body: it returns when the plan is ready. A walk that can't be made is not an "
            + "error: the plan's ok is false and why says why (and which leg); change the description (plan.spec is "
            + "what you gave) and plan again. Only a description written wrong raises bad_argument.")
    @Note("Cells needing your owner's consent are planned like any other at costs.consent times the price (default "
            + "10), and listed in each leg's asks; numen.move.go stops at each of them to ask. costs = {consent = false} "
            + "keeps away from them instead. Cells your owner's rules forbid are walls.")
    @Note("Without costs the walk changes no block: costs = {dig = true, place = true} lets it dig, pillar and bridge.")
    @Note("A plan is good only within this program: numen.move.go(plan) walks it, and the next program plans afresh. "
            + "Each leg is planned as far as one look reaches; a leg seen only in part is worked out on the way, "
            + "changing no cell the plan did not list.")
    @SeeAlso({"numen.move.go", "numen.move.to"})
    public static Pending<RouteText.Plan> plan(ServerCall call, Description.Directions directions) {
        NumenPlayer her = call.her();
        Description description = Description.of(directions);
        String program = Plans.program(call.callId());
        Plans plans = Plans.of(her);
        Planning planning = Planning.of(her, plans.nextId(program), description);
        Pending<RouteText.Plan> out = Pending.create();
        Plan now = planning.poll();
        if (now != null) {
            out.complete(planned(plans, program, now));
            return out;
        }
        RouteQueries.deliver(planning::poll, planning::cancel, plan -> out.complete(planned(plans, program, plan)));
        return out;
    }

    /** 计划记下(这一段程序里 {@code numen.move.go} 照它走),交回计划本身。走不通也是一份计划,不是失败。 */
    private static RouteText.Plan planned(Plans plans, String program, Plan plan) {
        plans.put(program, plan);
        return RouteText.data(plan);
    }
}
