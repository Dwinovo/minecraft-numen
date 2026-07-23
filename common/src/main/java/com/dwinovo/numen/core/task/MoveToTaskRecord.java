package com.dwinovo.numen.core.task;

import com.dwinovo.numen.task.TaskRecord;

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
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    public final Kind kind;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.kind = resolveKind(x, y, z, this.block);
    }

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    private static Kind resolveKind(Double x, Double y, Double z, String block) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
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
                + "or block alone (walk to the nearest block of that kind). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    @Override
    public String describe() {
        return switch (kind) {
            case BLOCK -> TOOL_NAME + " " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> TOOL_NAME + " x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> TOOL_NAME + " y=" + (int) (double) y;
            case FIND -> TOOL_NAME + " " + block;
        };
    }
}
