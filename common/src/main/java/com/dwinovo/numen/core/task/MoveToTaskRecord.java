package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for the {@code move_to} tool. The goal type is chosen
 * by WHICH coordinates are supplied (arg arity): the LLM picks its intent by
 * filling only the fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "move_to";

    public enum Kind { BLOCK, COLUMN, YLEVEL }

    /**
     * How a BLOCK move finishes (the LLM's optional override; {@link #AUTO}
     * when omitted):
     * <ul>
     *   <li>{@link #AUTO} — infer from the cell: solid → stop beside it
     *       (untouched), free → stand in it;</li>
     *   <li>{@link #INTERACT} — treat the cell as a block to use even if it is
     *       currently free: stop beside, keep it sacred;</li>
     *   <li>{@link #STAND_ON} — occupy that exact cell, digging into it if
     *       needed (subject to the normal / modify_terrain break rules);</li>
     *   <li>{@link #NEAR} — anywhere within the near-success radius counts.</li>
     * </ul>
     */
    public enum Arrival { AUTO, INTERACT, STAND_ON, NEAR }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** PathNavigation speed multiplier; 1.0 ≈ entity's MOVEMENT_SPEED attribute. */
    public final double speed;
    public final Kind kind;
    /** Force-break gate. false (default): normal breaking — the journey digs and
     *  bridges, but only breaks blocks some inventory tool actually harvests; a route
     *  that would need a no-drop grind (stone, no pickaxe) is refused with a
     *  diagnosis. true: break anything breakable, including the slow wrong-tool
     *  grind that drops nothing. */
    public final boolean modifyTerrain;
    /** How a BLOCK move finishes; {@link Arrival#AUTO} unless the LLM overrode it. */
    public final Arrival arrival;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, double speed,
                            boolean modifyTerrain, String arrival) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.speed = speed;
        this.modifyTerrain = modifyTerrain;
        this.kind = resolveKind(x, y, z);
        this.arrival = resolveArrival(arrival, this.kind);
    }

    /** Parse the optional arrival override; teaching errors for an unknown value
     *  or an override on a move kind that has no cell to arrive at. */
    private static Arrival resolveArrival(String raw, Kind kind) {
        if (raw == null || raw.isBlank()) {
            return Arrival.AUTO;
        }
        Arrival parsed = switch (raw) {
            case "interact" -> Arrival.INTERACT;
            case "stand_on" -> Arrival.STAND_ON;
            case "near" -> Arrival.NEAR;
            default -> throw new IllegalArgumentException(
                    "unknown arrival '" + raw + "' — use interact, stand_on or near,"
                    + " or omit it for auto.");
        };
        if (kind != Kind.BLOCK) {
            throw new IllegalArgumentException(
                    "arrival only applies to an exact x+y+z target; a "
                    + (kind == Kind.COLUMN ? "location (x+z)" : "height (y-only)")
                    + " move has no cell to arrive at — omit arrival for it.");
        }
        return parsed;
    }

    /**
     * Map supplied-axes → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    private static Kind resolveKind(Double x, Double y, Double z) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "move_to needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), or y alone (a target height). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    @Override
    public String describe() {
        return switch (kind) {
            case BLOCK -> TOOL_NAME + " " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> TOOL_NAME + " x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> TOOL_NAME + " y=" + (int) (double) y;
        };
    }
}
