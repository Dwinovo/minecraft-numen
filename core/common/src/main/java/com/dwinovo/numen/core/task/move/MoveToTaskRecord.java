package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for the {@code goto} tool. The goal type is chosen
 * by WHICH inputs are supplied: the LLM picks its intent by filling only the
 * fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 *   <li>{@code block} only (no coordinates) → {@link Kind#FIND}:
 *       scan for the nearest block of that kind and walk up beside it,
 *       never touching it.</li>
 *   <li>{@code route} only → {@link Kind#ROUTE}: walk a route the planner
 *       already priced (a {@code goto} refusal or a {@code plan_route} reply
 *       listed it by id); destination and spec are the route's own.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 *
 * <p>{@link #spec} is the parsed route spec the walk searches and executes under
 * ({@link RouteSpec#defaults()} = never changes a block). It is {@code null} only for
 * the ROUTE form, whose spec travels with the route.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND, ROUTE }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    /** Route-book id to walk; null unless this is the ROUTE form. */
    public final String route;
    public final Kind kind;
    /** The parsed route spec for a coordinate/FIND walk; null for the ROUTE form. */
    public final RouteSpec spec;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, RouteSpec spec, String route) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.route = route == null || route.isBlank() ? null : route.trim();
        this.kind = resolveKind(x, y, z, this.block, this.route);
        this.spec = spec;
    }

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    public static Kind resolveKind(Double x, Double y, Double z, String block, String route) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (route != null) {
            if (hasX || hasY || hasZ || block != null) {
                throw new IllegalArgumentException(
                        "route means 'walk the planned route " + route + "' — its destination and"
                        + " spec are already fixed, so give it ALONE (no coordinates, block or spec).");
            }
            return Kind.ROUTE;
        }
        if (block != null) {
            if (hasX || hasY || hasZ) {
                throw new IllegalArgumentException(
                        "block means 'walk to the nearest one of these' — no coordinates with"
                        + " it. To reach one specific block you know the position of, goto its"
                        + " location (x+z) and interact there.");
            }
            return Kind.FIND;
        }
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "goto needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), y alone (a target height), "
                + "block alone (walk to the nearest block of that kind), or route alone "
                + "(walk a planned route by id). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    /**
     * The navigation contract of a coordinate kind — the ONE place a goto target becomes a
     * goal, shared by the walk and by the read-only planner:
     * BLOCK = occupy exactly that cell (digging out whatever is there is the route's business,
     * so the cell is not sacred), COLUMN = that (x,z) at any height, YLEVEL = that height.
     */
    public static GoalCompiler.Compiled compile(Kind kind, int bx, int by, int bz) {
        return switch (kind) {
            case BLOCK -> GoalCompiler.standOn(new BlockPos(bx, by, bz));
            case COLUMN -> new GoalCompiler.Compiled(NavGoal.column(bx, bz), LongSets.emptySet());
            case YLEVEL -> new GoalCompiler.Compiled(NavGoal.yLevel(by), LongSets.emptySet());
            case FIND, ROUTE -> throw new IllegalArgumentException(kind + " has no coordinate goal");
        };
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        String where = switch (kind) {
            case BLOCK -> "走向 " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> "走向 x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> "下到 y=" + (int) (double) y;
            case FIND -> "去找 " + block;
            case ROUTE -> "走路线 " + route;
        };
        return spec != null && spec.alter().mayAlter() ? where + "(可开路)" : where;
    }
}
