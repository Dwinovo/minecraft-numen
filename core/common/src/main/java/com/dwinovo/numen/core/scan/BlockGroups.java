package com.dwinovo.numen.core.scan;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把一次搜索的命中分成"团":按 3×3×3 邻域相连(对角也算)的格子算同一团。这里只做几何与记账,不判权限,也不猜"这是不是谁的建筑"
 * ——团只由相连定。
 *
 * <p>{@link #add} 时就把新格与已收的邻格并起来(并查集),分团的活因此可以逐格跨 tick 摊开做。
 */
public final class BlockGroups {

    /**
     * 超过这么多格的团按 section 立方体(16³,与区块对齐)切成几块,各自成团;不超过的整团保留,跨区块边界
     * 也不切。取 256:一棵巨型云杉或丛林树的原木、一条大矿脉都在这之内,切开就把"一棵树"拆成两团;
     * 比这还大的已经是地形(一片水域、一整层石头),按 section 分块说"在哪一片"比一个横跨上百格的包围盒
     * 有用,也让一团的量有个边。
     */
    public static final int SPLIT_ABOVE = 256;

    /**
     * 一团。
     *
     * @param cells    每一格和看到的方块状态,由近及远
     * @param nearest  离中心最近的那一格
     * @param distance 那一格离中心的距离
     */
    public record Group(Map<BlockPos, BlockState> cells, BlockPos nearest, double distance) {}

    private final Long2IntOpenHashMap indexOf = new Long2IntOpenHashMap();
    private final LongArrayList cells = new LongArrayList();
    private final List<BlockState> states = new ArrayList<>();
    private final IntArrayList parent = new IntArrayList();

    public BlockGroups() {
        indexOf.defaultReturnValue(-1);
    }

    /** 收一格和它的方块状态。 */
    public void add(BlockPos pos, BlockState state) {
        long key = pos.asLong();
        int i = cells.size();
        cells.add(key);
        states.add(state);
        parent.add(i);
        indexOf.put(key, i);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    int j = indexOf.get(BlockPos.offset(key, dx, dy, dz));
                    if (j >= 0) {
                        union(i, j);
                    }
                }
            }
        }
    }

    /** 收了多少格。 */
    public int size() {
        return cells.size();
    }

    /** 分团:连通块,超过 {@link #SPLIT_ABOVE} 的按 section 切块;按离 {@code center} 最近的那一格由近及远排。 */
    public List<Group> grouped(BlockPos center) {
        Int2ObjectLinkedOpenHashMap<IntArrayList> components = new Int2ObjectLinkedOpenHashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            components.computeIfAbsent(find(i), k -> new IntArrayList()).add(i);
        }
        List<IntArrayList> pieces = new ArrayList<>();
        for (IntArrayList component : components.values()) {
            if (component.size() <= SPLIT_ABOVE) {
                pieces.add(component);
                continue;
            }
            Long2ObjectLinkedOpenHashMap<IntArrayList> bySection = new Long2ObjectLinkedOpenHashMap<>();
            for (int k = 0; k < component.size(); k++) {
                int i = component.getInt(k);
                bySection.computeIfAbsent(SectionPos.blockToSection(cells.getLong(i)), s -> new IntArrayList()).add(i);
            }
            pieces.addAll(bySection.values());
        }
        long[] nearestSq = new long[pieces.size()];
        int[] order = new int[pieces.size()];
        for (int p = 0; p < pieces.size(); p++) {
            order[p] = p;
            long best = Long.MAX_VALUE;
            IntArrayList piece = pieces.get(p);
            for (int k = 0; k < piece.size(); k++) {
                best = Math.min(best, distSq(piece.getInt(k), center));
            }
            nearestSq[p] = best;
        }
        IntArrays.quickSort(order, (a, b) -> Long.compare(nearestSq[a], nearestSq[b]));
        List<Group> groups = new ArrayList<>(order.length);
        for (int k : order) {
            groups.add(build(pieces.get(k), center));
        }
        return groups;
    }

    private Group build(IntArrayList piece, BlockPos center) {
        int[] members = piece.toIntArray();
        IntArrays.quickSort(members, (a, b) -> Long.compare(distSq(a, center), distSq(b, center)));
        Map<BlockPos, BlockState> byCell = new LinkedHashMap<>();
        for (int i : members) {
            byCell.put(BlockPos.of(cells.getLong(i)), states.get(i));
        }
        int first = members[0];
        return new Group(Collections.unmodifiableMap(byCell), BlockPos.of(cells.getLong(first)),
                Math.sqrt(distSq(first, center)));
    }

    private long distSq(int i, BlockPos center) {
        long packed = cells.getLong(i);
        long dx = BlockPos.getX(packed) - center.getX();
        long dy = BlockPos.getY(packed) - center.getY();
        long dz = BlockPos.getZ(packed) - center.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private int find(int i) {
        int root = i;
        while (parent.getInt(root) != root) {
            root = parent.getInt(root);
        }
        while (parent.getInt(i) != root) {
            int next = parent.getInt(i);
            parent.set(i, root);
            i = next;
        }
        return root;
    }

    private void union(int a, int b) {
        int ra = find(a);
        int rb = find(b);
        if (ra != rb) {
            parent.set(Math.max(ra, rb), Math.min(ra, rb));
        }
    }
}
