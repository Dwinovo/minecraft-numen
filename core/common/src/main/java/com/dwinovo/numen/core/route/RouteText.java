package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.dwinovo.numen.agent.script.JsonValues;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.pathing.plan.Edit;
import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.permission.Listing;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * 计划怎么交给她:程序拿到的数据({@link #data},样子是 {@link #PLAN})与回执里的那段话({@link #text}),两样从同一份计划写出,只在
 * 这里。要改的格与实际账同一种写法({@link NavText#planned});走不通的原因是寻路结局的原话,规划时已经写进计划。
 */
public final class RouteText {

    private RouteText() {}

    private static final Gson GSON = new Gson();

    /** 路上的一步。 */
    public static final ScriptType.Class STEP = new ScriptType.Class("Step", "One step of a planned way.", null, List.of(
            ScriptType.field("pos", Shapes.POS.type(), "Where the step ends: the cell your feet are in."),
            ScriptType.field("move", ScriptType.choice(List.of("walk", "jump", "fall", "swim", "climb", "dig",
                    "place", "sail")), "How: walk, jump, fall, swim, climb, dig (breaks its way), place (pillars or "
                    + "bridges), sail (in the boat).")));

    /** 要问主人的一格。 */
    public static final ScriptType.Class ASK = new ScriptType.Class("Ask",
            "A cell the walk changes that needs your owner's consent: a Block with why. The walk stops there and asks; "
                    + "give it to avoid (avoid = {ask}) to plan around it instead.", Shapes.BLOCK.name(),
            List.of(ScriptType.field("why", ScriptType.STRING, "Why it needs consent, in the permission layer's "
                    + "words (placed by a player …).")));

    /** 计划里的一段。 */
    public static final ScriptType.Class LEG = new ScriptType.Class("Leg", "One leg of a plan: the way to one stop.",
            null, List.of(
                    ScriptType.optional("to", Shapes.POS.type(), "The cell it heads for."),
                    ScriptType.field("reach", ScriptType.choice(List.of("reached", "partial", "unreachable",
                                    "unplanned")),
                            "reached: seen all the way. partial: seen as far as one look reaches (or the water "
                                    + "ends), worked out on the way. unreachable: can't be walked as described "
                                    + "(why). unplanned: after a partial or unreachable leg, not planned."),
                    ScriptType.optional("finish", Shapes.POS.type(), "Where the seen part ends."),
                    ScriptType.field("steps", ScriptType.INTEGER, null),
                    ScriptType.field("seconds", ScriptType.NUMBER, "About how long, as priced."),
                    ScriptType.field("path", ScriptType.listOf(STEP.type()), "Step by step; not printed with the "
                            + "plan, read it as leg.path."),
                    ScriptType.field("breaks", ScriptType.listOf(Shapes.BLOCK.type()), "Blocks it breaks."),
                    ScriptType.field("places", ScriptType.listOf(Shapes.BLOCK.type()), "Blocks it places (a water "
                            + "bucket poured to break a fall shows as water)."),
                    ScriptType.field("asks", ScriptType.listOf(ASK.type()), "Of those, the cells your owner is asked "
                            + "about when you get there."),
                    ScriptType.optional("dives", ScriptType.listOf(ScriptType.table(
                            ScriptType.field("from", Shapes.POS.type(), null),
                            ScriptType.field("to", Shapes.POS.type(), null),
                            ScriptType.field("seconds", ScriptType.NUMBER, "Without a breath."),
                            ScriptType.field("spare", ScriptType.NUMBER, "Seconds of air left after it."))),
                            "Stretches under water with no air on the way."),
                    ScriptType.optional("why", ScriptType.STRING, "Why it can't be walked, or why only part was "
                            + "seen.")));

    /** 一份计划。 */
    public static final ScriptType.Class PLAN = new ScriptType.Class("Plan",
            "A walk planned from where you stood, without moving: what numen.move.go walks and keeps to. Good only within "
                    + "the program that made it.", null, List.of(
                    ScriptType.field("ok", ScriptType.BOOLEAN, "No leg is unreachable: numen.move.go can walk it."),
                    ScriptType.optional("why", ScriptType.STRING, "When not ok: why the unreachable leg can't be "
                            + "walked."),
                    ScriptType.field("id", ScriptType.STRING, "Which plan this is, for numen.move.go."),
                    ScriptType.field("spec", new ScriptType.Simple("table"), "The description you gave, as given: change it and "
                            + "plan again."),
                    ScriptType.field("from", Shapes.POS.type(), "Where it was planned from."),
                    ScriptType.field("steps", ScriptType.INTEGER, "Steps of the seen part, all legs."),
                    ScriptType.field("seconds", ScriptType.NUMBER, "About how long the seen part takes."),
                    ScriptType.field("legs", ScriptType.listOf(LEG.type()), "One per stop, the destination last.")));

    // ==================== 数据 ====================

    /** 程序拿到的那份({@link #PLAN})。 */
    public static JsonObject data(Plan plan) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", plan.ok());
        if (!plan.ok()) {
            o.addProperty("why", plan.why());
        }
        o.addProperty("id", plan.id());
        o.add("spec", GSON.toJsonTree(plan.description().written()));
        o.add("from", Shapes.pos(plan.from()));
        o.addProperty("steps", plan.steps());
        o.addProperty("seconds", plan.seconds());
        JsonArray legs = new JsonArray();
        plan.legs().forEach(leg -> legs.add(legData(leg)));
        o.add("legs", legs);
        return o;
    }

    private static JsonObject legData(Plan.Leg leg) {
        JsonObject l = new JsonObject();
        BlockPos toward = leg.to() != null ? leg.to().toward()
                : leg.stop().to() instanceof Target.Cell cell ? cell.pos() : null;
        if (toward != null) {
            l.add("to", Shapes.pos(toward));
        }
        l.addProperty("reach", leg.reach().name().toLowerCase(Locale.ROOT));
        if (leg.finish() != null) {
            l.add("finish", Shapes.pos(leg.finish()));
        }
        l.addProperty("steps", leg.steps());
        l.addProperty("seconds", Math.round(leg.ticks() / 2.0) / 10.0);
        NavText.Changes changes = leg.changes();
        l.add("breaks", blocks(changes.digs()));
        l.add("places", blocks(changes.places()));
        JsonArray asks = new JsonArray();
        changes.asks().forEach((pos, cause) -> {
            Block block = changes.digs().containsKey(pos) ? changes.digs().get(pos) : changes.places().get(pos);
            JsonObject ask = Shapes.block(pos, block.defaultBlockState());
            ask.addProperty("why", cause);
            asks.add(ask);
        });
        l.add("asks", asks);
        if (leg.route() != null && !leg.route().dives().isEmpty()) {
            JsonArray dives = new JsonArray();
            for (Route.Dive dive : leg.route().dives()) {
                JsonObject d = new JsonObject();
                d.add("from", Shapes.pos(dive.from()));
                d.add("to", Shapes.pos(dive.to()));
                d.addProperty("seconds", Math.round(dive.held() / 2.0) / 10.0);
                d.addProperty("spare", Math.round(dive.left() / 2.0) / 10.0);
                dives.add(d);
            }
            l.add("dives", dives);
        }
        if (!leg.why().isEmpty()) {
            l.addProperty("why", leg.why());
        }
        JsonObject folded = new JsonObject();
        folded.add("path", path(leg));
        l.add(JsonValues.FOLDED, folded);
        return l;
    }

    /** 一步步:每一步落在哪一格、怎么走。 */
    private static JsonArray path(Plan.Leg leg) {
        JsonArray out = new JsonArray();
        if (leg.chart() != null) {
            for (BlockPos cell : leg.chart().path()) {
                out.add(step(cell, "sail"));
            }
        } else if (leg.route() != null) {
            for (Route.Leg step : leg.route().legs()) {
                out.add(step(step.maneuver().to(), move(step.maneuver())));
            }
        }
        return out;
    }

    private static JsonObject step(BlockPos pos, String move) {
        JsonObject s = new JsonObject();
        s.add("pos", Shapes.pos(pos));
        s.addProperty("move", move);
        return s;
    }

    /** 一步怎么走,说给她的那个词:手上要放的是 place,要挖的是 dig,其余照身体怎么挪。 */
    static String move(Maneuver m) {
        boolean places = m.edits().stream().anyMatch(e -> e instanceof Edit.Place || e instanceof Edit.Catch);
        boolean digs = m.edits().stream().anyMatch(e -> e instanceof Edit.Dig);
        if (places) {
            return "place";
        }
        if (digs) {
            return "dig";
        }
        return switch (m.kind()) {
            case WALK, DIAGONAL, DESCEND -> "walk";
            case ASCEND -> m.jump() ? "jump" : "walk";
            case PARKOUR -> "jump";
            case FALL -> "fall";
            case PILLAR -> "place";
            case DOWNWARD -> "dig";
            case CLIMB -> "climb";
            case SWIM -> "swim";
        };
    }

    private static JsonArray blocks(Map<BlockPos, Block> cells) {
        JsonArray out = new JsonArray();
        cells.forEach((pos, block) -> out.add(Shapes.block(pos, block.defaultBlockState())));
        return out;
    }

    // ==================== 话 ====================

    /**
     * 回执里的那段话:抬头(从哪儿、几段几步多久、走不走得通),每段一行,末尾是能照抄的下一步。
     */
    public static String text(Plan plan) {
        StringBuilder sb = new StringBuilder("planned from ").append(Listing.coords(plan.from())).append(": ")
                .append(plan.legs().size()).append(plan.legs().size() == 1 ? " leg, " : " legs, ").append(plan.steps())
                .append(" steps, about ").append(plan.seconds()).append(" s");
        sb.append(plan.ok() ? "." : "; it can't be walked as described.");
        for (int i = 0; i < plan.legs().size(); i++) {
            Plan.Leg leg = plan.legs().get(i);
            sb.append("\n  ").append(i + 1).append(". ").append(leg.stop().through() ? "through " : "")
                    .append(leg.stop().words()).append(": ").append(leg(leg));
        }
        sb.append('\n').append(next(plan));
        return sb.toString();
    }

    private static String leg(Plan.Leg leg) {
        return switch (leg.reach()) {
            case REACHED -> leg.steps() + " steps, about " + seconds(leg) + " s; " + changes(leg) + dives(leg);
            case PARTIAL -> (leg.finish() == null ? "unknown from its start"
                    : "known for " + leg.steps() + " steps up to " + Listing.coords(leg.finish()) + " (about "
                            + seconds(leg) + " s; " + changes(leg) + dives(leg) + "), unknown past that")
                    + ": " + leg.why();
            case UNREACHABLE -> "can't be walked: " + leg.why();
            case UNPLANNED -> "not planned, the leg before it is not seen to the end; it is worked out on the way";
        };
    }

    private static double seconds(Plan.Leg leg) {
        return Math.round(leg.ticks() / 2.0) / 10.0;
    }

    /**
     * 这一段要潜的水,每一段从哪儿到哪儿、一口气憋多久、憋完还剩多少气;不下水是空串。例如
     * {@code ; under water once: 10,40,5 to 20,40,5, about 12 s without a breath, 3 s of air left}。
     */
    private static String dives(Plan.Leg leg) {
        if (leg.route() == null || leg.route().dives().isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (Route.Dive dive : leg.route().dives()) {
            parts.add(Listing.coords(dive.from()) + " to " + Listing.coords(dive.to()) + ", about "
                    + NavText.seconds((int) Math.ceil(dive.held())) + " without a breath, "
                    + NavText.seconds((int) Math.floor(dive.left())) + " of air left");
        }
        return "; under water " + (parts.size() == 1 ? "once: " : parts.size() + " times: ") + String.join("; ", parts);
    }

    /** 这一段要动的格。 */
    private static String changes(Plan.Leg leg) {
        NavText.Changes changes = leg.changes();
        return NavText.planned(changes.digs(), changes.places(), changes.asks());
    }

    /** 计划之后能照抄的下一步。 */
    private static String next(Plan plan) {
        if (!plan.ok()) {
            return "Change what that leg's reason points at in the description (plan.spec) and numen.route.plan it again.";
        }
        int asks = plan.asks().size();
        String asking = asks == 0 ? "" : ", stopping to ask your owner at each of the " + asks + " cell(s) that need "
                + "their consent";
        boolean partial = plan.legs().stream().anyMatch(l -> l.reach() != Plan.Reach.REACHED);
        return "`numen.move.go(plan)` walks it" + asking + (partial ? ", working out the unseen part on the way" : "")
                + "; it changes only the cells listed here.";
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

    private static Map<BlockPos, Block> cells(List<Plan.Cell> cells) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        cells.forEach(c -> out.put(c.pos(), c.block()));
        return out;
    }
}
