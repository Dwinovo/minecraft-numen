package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.task.TaskRecord;

import net.minecraft.core.BlockPos;

/**
 * Typed task descriptor for {@code move goto} (shortcut {@code move_goto}). The goal type is chosen
 * by WHICH inputs are supplied: the LLM picks its intent by filling only the
 * fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location at whatever height stands there.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       stand in exactly that cell; a y in mid-air or inside a block is no place to stand, and the walk says so.</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 *   <li>{@code block} only (no coordinates) → {@link Kind#FIND}:
 *       scan for the nearest block of that kind and walk up beside it,
 *       never touching it.</li>
 *   <li>{@code route} only → {@link Kind#ROUTE}: walk a route the planner
 *       already priced (a {@code move goto} refusal or a {@code move route} reply
 *       listed it by id); destination and spec are the route's own.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class. {@link #near} widens a coordinate form to "anywhere within that many blocks":
 * around the cell with y, around the column without it — the only way to ask for "close enough", since arrival is
 * otherwise exact.
 *
 * <p>{@link #spec} is the parsed route spec the walk searches and executes under
 * ({@link RouteSpec#defaults()} = never changes a block). It is {@code null} only for
 * the ROUTE form, whose spec travels with the route.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND, ROUTE }

    /** 基础期限:30 秒,出发后按路程再往后推(见 {@code MoveToCompanionTask})。 */
    private static final long BUDGET_TICKS = 30 * 20;

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Integer x;
    public final Integer y;
    public final Integer z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    /** Route-book id to walk; null unless this is the ROUTE form. */
    public final String route;
    public final Kind kind;
    /** The parsed route spec for a coordinate/FIND walk; null for the ROUTE form. */
    public final RouteSpec spec;
    /** Accept anywhere within this many blocks of the coordinates; null = exactly there. */
    public final Integer near;

    public MoveToTaskRecord(ServerSource source, Integer x, Integer y, Integer z, String block, RouteSpec spec,
                            String route, Integer near) {
        super(source, source.companion().level().getGameTime() + BUDGET_TICKS);
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.route = route == null || route.isBlank() ? null : route.trim();
        this.kind = resolveKind(x, y, z, this.block, this.route);
        if (near != null && kind != Kind.BLOCK && kind != Kind.COLUMN) {
            throw new IllegalArgumentException("near widens a location (x and z, with or without y) to anywhere within"
                    + " that many blocks; it does not go with " + (kind == Kind.YLEVEL ? "y alone" : kind == Kind.FIND
                            ? "block" : "route") + ".");
        }
        this.spec = spec;
        this.near = near;
    }

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    public static Kind resolveKind(Integer x, Integer y, Integer z, String block, String route) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (route != null) {
            if (hasX || hasY || hasZ || block != null) {
                throw new IllegalArgumentException(
                        "route means 'walk the planned route " + route + "' — its destination and"
                        + " spec are already fixed, so give it ALONE (no coordinates, block or route-spec flags).");
            }
            return Kind.ROUTE;
        }
        if (block != null) {
            if (hasX || hasY || hasZ) {
                throw new IllegalArgumentException(
                        "block means 'walk to the nearest one of these' — no coordinates with"
                        + " it. To reach one specific block you know the position of, move_goto its"
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
                "move_goto needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), y alone (a target height), "
                + "block alone (walk to the nearest block of that kind), or route alone "
                + "(walk a planned route by id). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    /**
     * The goal of a coordinate kind — the ONE place a goto target becomes a goal, shared by the walk and by the
     * read-only planner: BLOCK = stand in exactly that cell, COLUMN = that (x,z) at any height, YLEVEL = that height;
     * with {@code near}, anywhere within that many blocks of the cell (BLOCK) or of the column (COLUMN).
     */
    public static Goal goal(Kind kind, int bx, int by, int bz, Integer near) {
        return switch (kind) {
            case BLOCK -> near == null ? Goals.at(new BlockPos(bx, by, bz)) : Goals.near(new BlockPos(bx, by, bz), near);
            case COLUMN -> near == null ? Goals.column(bx, bz) : Goals.ring(new BlockPos(bx, 0, bz), 0, near);
            case YLEVEL -> Goals.level(by);
            case FIND, ROUTE -> throw new IllegalArgumentException(kind + " has no coordinate goal");
        };
    }

    /**
     * 给人说"朝哪儿"的那一格(回执里的方向与距离):BLOCK 是那一格,COLUMN 是那一列上与 {@code from} 同高的一格,YLEVEL 是
     * {@code from} 那一列上的那个高度。
     */
    public static BlockPos toward(Kind kind, int bx, int by, int bz, BlockPos from) {
        return switch (kind) {
            case BLOCK -> new BlockPos(bx, by, bz);
            case COLUMN -> new BlockPos(bx, from.getY(), bz);
            case YLEVEL -> new BlockPos(from.getX(), by, from.getZ());
            case FIND, ROUTE -> throw new IllegalArgumentException(kind + " has no coordinate target");
        };
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        String where = switch (kind) {
            case BLOCK -> "走向 " + x + "," + y + "," + z;
            case COLUMN -> "走向 x=" + x + " z=" + z;
            case YLEVEL -> "下到 y=" + y;
            case FIND -> "去找 " + block;
            case ROUTE -> "走路线 " + route;
        };
        String within = near == null ? "" : "(" + near + " 格内)";
        return spec != null && spec.alter().mayAlter() ? where + within + "(可开路)" : where + within;
    }
}
