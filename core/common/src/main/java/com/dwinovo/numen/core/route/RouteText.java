package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.permission.Listing;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * 路线怎么说给模型:意图、计划、走过的记录、计划超出承诺的差别,都在这里,只此一处。要改的格与实际账同一种写法
 * ({@link NavText#planned});没走到的原因是寻路结局的原话,规划时已经写进计划。
 */
public final class RouteText {

    private RouteText() {}

    /** 途经点一串:{@code 1. 20,64,5  2. 30,70,5 (within 3)}。 */
    public static String stops(Itinerary route) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < route.legs().size(); i++) {
            sb.append(i == 0 ? "" : "  ").append(i + 1).append(". ").append(route.legs().get(i).to().words());
        }
        return sb.toString();
    }

    /** 意图:在哪个维度、途经点、规格标志。 */
    public static String intent(Itinerary route) {
        StringBuilder sb = new StringBuilder("route ").append(route.name()).append(" in ").append(route.dimension())
                .append(": starts wherever I stand, then ").append(stops(route))
                .append(route.legs().size() > 1 ? " (the last is the destination)" : "");
        List<String> flags = new ArrayList<>();
        if (!route.flags().isEmpty()) {
            flags.add(RouteFlags.shown(route.flags()) + " on the whole route");
        }
        for (int i = 0; i < route.legs().size(); i++) {
            if (!route.legs().get(i).flags().isEmpty()) {
                flags.add(RouteFlags.shown(route.legs().get(i).flags()) + " on leg " + (i + 1));
            }
        }
        sb.append(". Route flags: ").append(flags.isEmpty() ? "none, so it changes no block" : String.join("; ", flags))
                .append('.');
        return sb.toString();
    }

    /**
     * 一份计划:抬头(从哪儿、多久以前、几段几步多少刻),每段一行,末尾是能照抄的下一步。
     *
     * @param now 此刻(主世界游戏刻)
     */
    public static String plan(Itinerary route, Plan plan, long now) {
        return body(route, plan, now) + '\n' + next(route, plan);
    }

    /**
     * 受理回执里的计划:这一趟开走前刚做的那一份,写法与 {@code route plan} 的回执同一种(抬头、每段一行),末尾交代开走前
     * 要问主人几格。
     */
    public static String accepted(Itinerary route, Plan plan) {
        int asks = plan.asks().size();
        return "The " + body(route, plan, plan.at())
                + (asks == 0 ? "" : "\nBefore setting off I ask your owner about " + asks + " cell(s) of it.");
    }

    /** 计划的抬头(从哪儿、多久以前、几段几步多少刻)与每段一行。 */
    private static String body(Itinerary route, Plan plan, long now) {
        int steps = 0;
        int ticks = 0;
        for (Plan.Leg leg : plan.legs()) {
            steps += leg.steps();
            ticks += leg.ticks();
        }
        StringBuilder sb = new StringBuilder("plan of route ").append(route.name()).append(", made from ")
                .append(Listing.coords(plan.from())).append(' ').append(ago(now - plan.at())).append(": ")
                .append(plan.legs().size()).append(plan.legs().size() == 1 ? " leg, " : " legs, ").append(steps)
                .append(" steps, about ").append(ticks).append(" ticks as priced.");
        int offset = route.legs().size() - plan.legs().size();
        for (int i = 0; i < plan.legs().size(); i++) {
            sb.append("\n  leg ").append(offset + i + 1).append(" to ")
                    .append(route.legs().get(offset + i).to().words()).append(": ").append(leg(plan.legs().get(i)));
        }
        return sb.toString();
    }

    private static String leg(Plan.Leg leg) {
        return switch (leg.reach()) {
            case WALKABLE -> leg.steps() + " steps, about " + leg.ticks() + " ticks; " + changes(leg) + dives(leg);
            case PARTIAL -> (leg.end() == null ? "unknown from its start"
                    : "known for " + leg.steps() + " steps up to " + Listing.coords(leg.end()) + " (about "
                            + leg.ticks() + " ticks; " + changes(leg) + dives(leg) + "), unknown past that")
                    + ": " + leg.why();
            case UNREACHABLE -> "can't be walked: " + leg.why();
            case UNPLANNED -> "not planned yet, the leg before it is only partly known; it is worked out on the way";
        };
    }

    /**
     * 这一段要潜的水,每一段从哪儿到哪儿、一口气憋多久、憋完还剩多少气;不下水是空串。例如
     * {@code ; under water once: 10,40,5 to 20,40,5, about 12 s without a breath, 3 s of air left}。
     */
    private static String dives(Plan.Leg leg) {
        if (leg.dives().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Plan.Dive dive : leg.dives()) {
            parts.add(Listing.coords(dive.from()) + " to " + Listing.coords(dive.to()) + ", about "
                    + NavText.seconds(dive.held()) + " without a breath, " + NavText.seconds(dive.left()) + " of air left");
        }
        return "; under water " + (parts.size() == 1 ? "once: " : parts.size() + " times: ") + String.join("; ", parts);
    }

    /** 这一段要动的格。 */
    private static String changes(Plan.Leg leg) {
        return NavText.planned(cells(leg.digs()), cells(leg.places()), asks(leg.asks()));
    }

    /** 计划之后能照抄的下一步。 */
    private static String next(Itinerary route, Plan plan) {
        if (plan.unreachable() >= 0) {
            return "It can't be walked as it stands: that leg's reason says what would change it (route.spec, "
                    + "route.via, route.drop), then `route.plan(\"" + route.name() + "\")` again.";
        }
        int asks = plan.asks().size();
        String asking = asks == 0 ? "" : ", asking your owner about " + asks + " cell(s) before setting off";
        for (Plan.Leg leg : plan.legs()) {
            if (leg.reach() == Plan.Reach.PARTIAL) {
                return "`move.go(\"" + route.name() + "\")` walks it" + asking + ", working out the unknown part on "
                        + "the way; it changes only the cells listed here, and stops to say so if the unknown part needs "
                        + "more.";
            }
        }
        return "`move.go(\"" + route.name() + "\")` walks it" + asking + "; it changes only the cells listed here.";
    }

    /** 走过的记录,新的在前;没走过是空串。 */
    public static String walks(Itinerary route, long now) {
        if (route.walks().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (int i = route.walks().size() - 1; i >= 0; i--) {
            Itinerary.Walk walk = route.walks().get(i);
            parts.add(walk.who() + " " + ago(now - walk.at()) + ": " + walk.end() + " after " + walk.ticks() + " ticks");
        }
        return "walked: " + String.join("; ", parts) + ".";
    }

    /** {@code route list} 里的一行:终点、几段、计划、走过几次与上一次。 */
    public static String row(Itinerary route, long now) {
        StringBuilder sb = new StringBuilder("  ").append(route.name()).append(" — to ")
                .append(route.destination().words()).append(", ").append(route.legs().size())
                .append(route.legs().size() == 1 ? " leg" : " legs").append(", ");
        Plan plan = route.plan();
        if (plan == null) {
            sb.append("not planned");
        } else {
            sb.append("planned ").append(ago(now - plan.at())).append(" (")
                    .append(plan.unreachable() >= 0 ? "can't be walked"
                            : plan.legs().stream().anyMatch(l -> l.reach() == Plan.Reach.PARTIAL) ? "partly known"
                            : "walkable")
                    .append(')');
        }
        if (!route.walks().isEmpty()) {
            Itinerary.Walk last = route.walks().get(route.walks().size() - 1);
            sb.append(", walked ").append(route.walks().size()).append(route.walks().size() == 1 ? " time" : " times")
                    .append(", last ").append(ago(now - last.at())).append(": ").append(last.end());
        }
        return sb.toString();
    }

    /** 从这里规划出来的路超出承诺的那些格,一句。 */
    public static String beyond(Plan.Difference diff) {
        List<String> parts = new ArrayList<>();
        if (!diff.digs().isEmpty() || !diff.places().isEmpty()) {
            parts.add(NavText.planned(cells(diff.digs()), cells(diff.places()), Map.of()));
        }
        if (!diff.asks().isEmpty()) {
            List<BlockPos> at = diff.asks().stream().map(Plan.Ask::pos).toList();
            parts.add("ask your owner about " + Listing.part("cell(s)", at.size(), at));
        }
        return String.join("; ", parts);
    }

    /** 多久以前:按游戏刻换算成秒、分、时、天。 */
    static String ago(long ticks) {
        long seconds = Math.max(0, ticks) / 20;
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " min ago";
        }
        long hours = minutes / 60;
        return hours < 48 ? hours + " h ago" : hours / 24 + " days ago";
    }

    private static Map<BlockPos, Block> cells(List<Plan.Cell> cells) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        cells.forEach(c -> out.put(c.pos(), c.block()));
        return out;
    }

    private static Map<BlockPos, String> asks(List<Plan.Ask> asks) {
        Map<BlockPos, String> out = new LinkedHashMap<>();
        asks.forEach(a -> out.put(a.pos(), a.cause()));
        return out;
    }
}
