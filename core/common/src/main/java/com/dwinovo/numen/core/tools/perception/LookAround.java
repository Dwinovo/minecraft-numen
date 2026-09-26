package com.dwinovo.numen.core.tools.perception;

import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.core.pathing.execute.PathExecutor;
import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Egocentric spatial view: renders the blocks around the companion as an
 * agent-centred character grid (top-down, one cell per block, North up) rather
 * than a flat coordinate list. Each cell is semantic-pooled to the movement
 * class AND the vertical affordance at the companion's Y band — flat / step-up /
 * step-down / drop / wall / water / lava — so the model reads terrain, obstacles,
 * gaps and jumpable ledges as a map instead of probing single cells.
 *
 * <p>The representation follows the egocentric semantic grid shown to help LLM
 * spatial reasoning in Gao et al., "Exploring Spatial Representation to Enhance
 * LLM Reasoning in Aerial Vision-Language Navigation" (arXiv:2410.08500). The
 * vertical affordance encoding (collapsing a few height slices into one movement
 * symbol) and the hazard "inflation" buffer follow the occupancy-grid / layered
 * costmap practice in autonomous-driving navigation (e.g. Occ3D; ROS Nav2
 * costmap_2d). Sparse far-field objects are left to {@code scan blocks} /
 * {@code scan entities}; this map is the dense near-field half. The command and its
 * shortcut ({@code scan around} / {@code look_around}) are declared in {@link ScanCommands}.
 */
final class LookAround {

    static final int DEFAULT_RADIUS = 8;
    static final int MIN_RADIUS = 4;
    static final int MAX_RADIUS = 16;
    /** How far below foot level a floor may sit before the cell reads as a drop. */
    private static final int DROP_DEPTH = 3;

    // Cell glyphs.
    private static final char YOU = '@';
    private static final char FLAT = '.';       // walkable, same level
    private static final char STEP_UP = '^';    // walkable by a 1-block jump up
    private static final char STEP_DOWN = ',';  // walkable, 1-2 blocks down
    private static final char DROP = 'v';        // drop of DROP_DEPTH+ blocks
    private static final char WALL = '#';        // blocked / step up >= 2
    private static final char WATER = '~';
    private static final char HAZARD = '!';      // lava / fire
    private static final char CAUTION = 'x';     // inflation buffer next to a hazard
    private static final char TREE = 'T';
    private static final char UNLOADED = '?';

    private LookAround() {}

    /** The map of the {@code (2 * radius + 1)}-wide square around her feet; {@code radius} is clamped to 4-16. */
    static String render(NumenPlayer self, int asked) {
        int radius = Math.clamp(asked, MIN_RADIUS, MAX_RADIUS);
        BlockGetter view = LoadedOnlyView.of(self.level());
        LoadedOnlyView loaded = view instanceof LoadedOnlyView v ? v : null;
        BlockPos center = PathExecutor.playerFeet(self);
        int cx = center.getX();
        int cy = center.getY();
        int cz = center.getZ();

        int size = 2 * radius + 1;
        char[][] grid = new char[size][size];
        for (int r = 0; r < size; r++) {
            int dz = r - radius;                 // r=0 is north (-Z), top
            for (int c = 0; c < size; c++) {
                int dx = c - radius;             // c=0 is west (-X), left
                grid[r][c] = (dx == 0 && dz == 0)
                        ? YOU
                        : classify(view, loaded, cx + dx, cy, cz + dz);
            }
        }
        inflateHazards(grid, size);

        StringBuilder sb = new StringBuilder();
        sb.append("look_around center=(").append(cx).append(',').append(cy).append(',').append(cz)
                .append(") facing=").append(self.getDirection().getName())
                .append(" | 1 cell = 1 block, @ = you, North = up (-Z), East = right (+X)\n\n");
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                sb.append(grid[r][c]);
                if (c < size - 1) {
                    sb.append(' ');
                }
            }
            sb.append('\n');
        }
        sb.append("\nlegend: @ you | . flat | ^ step-up 1 | , step-down 1-2 | v drop>=").append(DROP_DEPTH)
                .append(" | # wall/blocked | ~ water | ! lava/hazard | x caution | T tree | ? unloaded\n")
                .append("to route: trace cell by cell (. ^ , are walkable; # ~ ! v x block or endanger you).\n");
        return sb.toString();
    }

    /** Semantic-pool the column at (x,z) to one movement-affordance glyph at the companion's Y band. */
    private static char classify(BlockGetter view, LoadedOnlyView loaded, int x, int feetY, int z) {
        if (loaded != null && !loaded.isLoaded(x, z)) {
            return UNLOADED;
        }
        BlockState feetState = view.getBlockState(new BlockPos(x, feetY, z));
        BlockState headState = view.getBlockState(new BlockPos(x, feetY + 1, z));

        if (CellClass.isLava(feetState) || CellClass.isLava(headState)) {
            return HAZARD;
        }
        if (feetState.getBlock() instanceof LiquidBlock || headState.getBlock() instanceof LiquidBlock) {
            return WATER;
        }

        // Highest surface you could stand on within a jump-up / short-drop band. Going down the column, the
        // first liquid you would step onto is the surface: a lake level with the shore is water, not a pit.
        Integer standY = null;
        for (int y = feetY + 1; y >= feetY - DROP_DEPTH; y--) {
            if (canStandAt(view, x, y, z)) {
                standY = y;
                break;
            }
            BlockState floor = view.getBlockState(new BlockPos(x, y - 1, z));
            if (y <= feetY && CellClass.isLava(floor)) {
                return HAZARD;
            }
            if (y <= feetY && floor.getBlock() instanceof LiquidBlock) {
                return WATER;
            }
        }
        if (standY == null) {
            boolean bodyClear = CellClass.fullyPassable(view, new BlockPos(x, feetY, z))
                    && CellClass.fullyPassable(view, new BlockPos(x, feetY + 1, z));
            if (!bodyClear) {
                return (isTree(feetState) || isTree(headState)) ? TREE : WALL;
            }
            return DROP; // body clear but no floor within reach -> open pit/void
        }
        int delta = standY - feetY;
        if (delta >= 2) {
            return WALL;
        }
        if (delta == 1) {
            return STEP_UP;
        }
        if (delta == 0) {
            return FLAT;
        }
        if (delta >= -2) {
            return STEP_DOWN;
        }
        return DROP;
    }

    private static boolean canStandAt(BlockGetter view, int x, int y, int z) {
        return CellClass.canWalkOn(view, new BlockPos(x, y - 1, z), RouteSpec.defaults())
                && CellClass.fullyPassable(view, new BlockPos(x, y, z))
                && CellClass.fullyPassable(view, new BlockPos(x, y + 1, z));
    }

    /** Layered-costmap style: ring a caution buffer around lava/fire so the model keeps clear of edges. */
    private static void inflateHazards(char[][] grid, int size) {
        boolean[][] near = new boolean[size][size];
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (grid[r][c] == HAZARD) {
                    for (int dr = -1; dr <= 1; dr++) {
                        for (int dc = -1; dc <= 1; dc++) {
                            int nr = r + dr;
                            int nc = c + dc;
                            if (nr >= 0 && nr < size && nc >= 0 && nc < size) {
                                near[nr][nc] = true;
                            }
                        }
                    }
                }
            }
        }
        for (int r = 0; r < size; r++) {
            for (int c = 0; c < size; c++) {
                if (near[r][c] && isWalkable(grid[r][c])) {
                    grid[r][c] = CAUTION;
                }
            }
        }
    }

    private static boolean isWalkable(char c) {
        return c == FLAT || c == STEP_UP || c == STEP_DOWN;
    }

    private static boolean isTree(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }
}
