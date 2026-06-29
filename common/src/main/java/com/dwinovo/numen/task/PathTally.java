package com.dwinovo.numen.task;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-task tally of terrain a task modified <em>while travelling</em> —
 * obstruction blocks dug through and scaffolding blocks placed incidentally by a
 * navigating task. Lives on the entity (not on any one task) so it survives the
 * task churn of mid-path replans; {@code CompanionTickDispatcher} resets it at
 * task start and a task may fold it into its result so the model learns the real
 * side effects of a move ("reached the target, but dug 4 dirt and spent 6
 * cobblestone bridging") rather than treating navigation as free.
 *
 * <p>Engine-level bookkeeping with no behaviour of its own: a task that does no
 * incidental terrain work simply leaves it empty. The pathfinding tools that
 * actually populate it ship in {@code numen-core}, not here.
 */
public final class PathTally {

    private final Map<Block, Integer> dug = new LinkedHashMap<>();
    private final Map<Block, Integer> placed = new LinkedHashMap<>();

    public void reset() {
        dug.clear();
        placed.clear();
    }

    public void addDug(Block block) {
        dug.merge(block, 1, Integer::sum);
    }

    public void addPlaced(Block block) {
        placed.merge(block, 1, Integer::sum);
    }

    public boolean isEmpty() {
        return dug.isEmpty() && placed.isEmpty();
    }

    /** e.g. "3x dirt, 1x stone"; empty string when nothing was dug. */
    public String describeDug() {
        return format(dug);
    }

    /** e.g. "6x cobblestone"; empty string when nothing was placed. */
    public String describePlaced() {
        return format(placed);
    }

    private static String format(Map<Block, Integer> counts) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Block, Integer> e : counts.entrySet()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(e.getValue()).append("x ")
                    .append(BuiltInRegistries.BLOCK.getKey(e.getKey()).getPath());
        }
        return sb.toString();
    }
}
