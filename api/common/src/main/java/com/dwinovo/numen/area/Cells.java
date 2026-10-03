package com.dwinovo.numen.area;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 一堆格子(不带维度),每格可附带"当时看到的方块与游戏刻"。不可变值:运算都交回新值,任何线程可读。
 *
 * <h2>存法</h2>
 * 和原版存区块同一个思路:按 16×16×16 小节({@link SectionPos#asLong} 作键)存位图,每节 4096 位、{@code long[64]},
 * 节内第 {@code y<<8 | z<<4 | x} 位是那一格。于是一行(同一 y、z 的 16 格)正好是某个 long 里对齐的 16 位,
 * 平移与外扩按行做位运算;"一格在不在"是一次查表加一次取位。四百万格的基地只有几百到一千来节。
 *
 * <p>附带的方块只给扫描来的格存:按节一张调色板({@link Seen} 去重),加一张每格的调色板下标;
 * 一节里没有一格附带方块,就两样都不存。附带的是<b>当时的</b>:这里不读世界,消费方动手时按活世界复核。
 *
 * <h2>运算</h2>
 * 并、差、交逐节位运算;按方块筛、外扩、平移同样按节按行。同一格两边都附带了方块时留较新的那一次(游戏刻大的),
 * 一样新留左边的。
 */
public final class Cells {

    /** 扫描时这一格是什么、在哪一刻看到的。 */
    public record Seen(BlockState state, long tick) {

        /**
         * 这一格现在还是当时看到的那种方块:比方块种类,不比朝向、含水这类状态——原木换了朝向还是那根原木。消费方动手前
         * (挖之前)都按这一条认,不各写一份。
         */
        public boolean holds(BlockState now) {
            return now.getBlock() == state.getBlock();
        }
    }

    /** 逐格遍历的回调;没附带方块的格 {@code seen} 为 null。 */
    @FunctionalInterface
    public interface Visitor {
        void visit(int x, int y, int z, Seen seen);
    }

    public static final Cells EMPTY = new Cells(new Long2ObjectOpenHashMap<>());

    private static final int LONGS = 64;
    private static final int CELLS = 4096;
    private static final int LANES = 256;

    /** 一节:位图、可选的调色板与每格下标、格数。构造后不再改。 */
    private static final class Section {
        final long[] bits;
        /** null:这一节没有一格附带方块。 */
        final Seen[] palette;
        /** 与 {@link #palette} 同为 null 或同在;0 是没附带,{@code n} 是 {@code palette[n-1]}。 */
        final short[] seen;
        final int count;

        Section(long[] bits, Seen[] palette, short[] seen, int count) {
            this.bits = bits;
            this.palette = palette;
            this.seen = seen;
            this.count = count;
        }

        boolean has(int i) {
            return (bits[i >>> 6] >>> (i & 63) & 1L) != 0;
        }

        Seen seenAt(int i) {
            if (seen == null) {
                return null;
            }
            int n = seen[i];
            return n == 0 ? null : palette[n - 1];
        }

        int lane(int lane) {
            return (int) (bits[lane >>> 2] >>> ((lane & 3) << 4)) & 0xFFFF;
        }
    }

    private final Long2ObjectOpenHashMap<Section> sections;
    /** 各节的键,排好序:遍历与存盘的顺序由它定,同一堆格子每次都一样。 */
    private final long[] keys;
    private final long size;

    private Cells(Long2ObjectOpenHashMap<Section> sections) {
        this.sections = sections;
        this.keys = sections.keySet().toLongArray();
        Arrays.sort(keys);
        long n = 0;
        for (Section s : sections.values()) {
            n += s.count;
        }
        this.size = n;
    }

    // ==================== 构造 ====================

    /** 两个对角(含)框出的盒子。 */
    public static Cells box(BlockPos a, BlockPos b) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        Builder out = new Builder();
        for (int y = Math.min(a.getY(), b.getY()); y <= Math.max(a.getY(), b.getY()); y++) {
            for (int z = Math.min(a.getZ(), b.getZ()); z <= Math.max(a.getZ(), b.getZ()); z++) {
                out.row(y, z, x0, x1);
            }
        }
        return out.build();
    }

    /** 一格。 */
    public static Cells point(BlockPos pos) {
        Builder out = new Builder();
        out.add(pos.getX(), pos.getY(), pos.getZ(), null);
        return out.build();
    }

    /** 以 {@code center} 为中心、半径 {@code radius} 的球:到中心的距离平方不超过 {@code radius²} 的格,与工作区同一个量法。 */
    public static Cells sphere(BlockPos center, int radius) {
        if (radius < 0) {
            throw new IllegalArgumentException("a sphere's radius is 0 or more, got " + radius);
        }
        Builder out = new Builder();
        long r2 = (long) radius * radius;
        for (int dy = -radius; dy <= radius; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                long rest = r2 - (long) dy * dy - (long) dz * dz;
                if (rest < 0) {
                    continue;
                }
                int w = (int) Math.floor(Math.sqrt((double) rest));
                out.row(center.getY() + dy, center.getZ() + dz, center.getX() - w, center.getX() + w);
            }
        }
        return out.build();
    }

    /** 框出来的一组格,不附带方块。 */
    public static Cells of(Iterable<BlockPos> cells) {
        Builder out = new Builder();
        for (BlockPos p : cells) {
            out.add(p.getX(), p.getY(), p.getZ(), null);
        }
        return out.build();
    }

    /** 扫描来的一组格:每格附带在第 {@code tick} 刻看到的方块。 */
    public static Cells seen(Map<BlockPos, BlockState> blocks, long tick) {
        Builder out = new Builder();
        Map<BlockState, Seen> shared = new HashMap<>();
        blocks.forEach((p, state) -> out.add(p.getX(), p.getY(), p.getZ(),
                shared.computeIfAbsent(state, s -> new Seen(s, tick))));
        return out.build();
    }

    // ==================== 查询 ====================

    public boolean contains(BlockPos pos) {
        return contains(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 这一格在不在:查一次节、取一位。 */
    public boolean contains(int x, int y, int z) {
        Section s = sections.get(key(x, y, z));
        return s != null && s.has(index(x, y, z));
    }

    /** 这一格附带的方块;不在里面或没附带是 null。 */
    public Seen seenAt(BlockPos pos) {
        Section s = sections.get(key(pos.getX(), pos.getY(), pos.getZ()));
        int i = index(pos.getX(), pos.getY(), pos.getZ());
        return s == null || !s.has(i) ? null : s.seenAt(i);
    }

    public long size() {
        return size;
    }

    public boolean isEmpty() {
        return size == 0;
    }

    /** 包住所有格子的最小盒子;空的是 null。 */
    public BoundingBox bounds() {
        if (isEmpty()) {
            return null;
        }
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long key : keys) {
            Section s = sections.get(key);
            int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key));
            int oy = SectionPos.sectionToBlockCoord(SectionPos.y(key));
            int oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
            for (int lane = 0; lane < LANES; lane++) {
                int row = s.lane(lane);
                if (row == 0) {
                    continue;
                }
                int y = oy + (lane >>> 4);
                int z = oz + (lane & 15);
                minX = Math.min(minX, ox + Integer.numberOfTrailingZeros(row));
                maxX = Math.max(maxX, ox + 31 - Integer.numberOfLeadingZeros(row));
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        }
        return new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 逐格遍历,顺序每次一样。 */
    public void forEach(Visitor visitor) {
        for (long key : keys) {
            visitSection(key, sections.get(key), visitor);
        }
    }

    /** 离 {@code from} 最近的一格(直线距离);一样近的取遍历顺序在前的。空的是 null。 */
    public BlockPos nearest(BlockPos from) {
        return nearest(from.getX(), from.getY(), from.getZ());
    }

    /**
     * 离 {@code (px, py, pz)} 最近的一格。按节到这一点的最近可能距离由近到远看,一节的下界已经不比找到的近就停,
     * 大区域也只翻得着附近几节。
     */
    public BlockPos nearest(double px, double py, double pz) {
        if (isEmpty()) {
            return null;
        }
        double[] bound = new double[keys.length];
        Integer[] order = new Integer[keys.length];
        for (int k = 0; k < keys.length; k++) {
            order[k] = k;
            bound[k] = boxDistSqr(keys[k], px, py, pz);
        }
        Arrays.sort(order, (a, b) -> Double.compare(bound[a], bound[b]));
        double[] best = {Double.MAX_VALUE};
        int[] found = new int[3];
        for (int k : order) {
            if (bound[k] > best[0]) {
                break;
            }
            visitSection(keys[k], sections.get(keys[k]), (x, y, z, seen) -> {
                double dx = x - px, dy = y - py, dz = z - pz;
                double d = dx * dx + dy * dy + dz * dz;
                if (d < best[0]) {
                    best[0] = d;
                    found[0] = x;
                    found[1] = y;
                    found[2] = z;
                }
            });
        }
        return new BlockPos(found[0], found[1], found[2]);
    }

    /**
     * 离 {@code from} 最近的至多 {@code limit} 格(直线距离),由近到远;一样近的先后每次一样。和 {@link #nearest(BlockPos)} 同一个
     * 走法:按节到这一点的最近可能距离由近到远看,攒够了 {@code limit} 格、下一节的下界又比攒到的最远一格还远就停——四百万格的
     * 区域也只翻离这一点近的那几节,交出的格数不超过 {@code limit}。
     */
    public List<BlockPos> nearest(BlockPos from, int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("ask for 1 or more cells, got " + limit);
        }
        if (isEmpty()) {
            return List.of();
        }
        double px = from.getX();
        double py = from.getY();
        double pz = from.getZ();
        double[] bound = new double[keys.length];
        Integer[] order = new Integer[keys.length];
        for (int k = 0; k < keys.length; k++) {
            order[k] = k;
            bound[k] = boxDistSqr(keys[k], px, py, pz);
        }
        Arrays.sort(order, (a, b) -> Double.compare(bound[a], bound[b]));
        // 攒到的格:最远的在堆顶,满了就拿更近的换掉它;先后号让一样近的按遍历顺序排
        java.util.PriorityQueue<long[]> kept = new java.util.PriorityQueue<>(limit, (a, b) -> a[0] != b[0]
                ? Long.compare(b[0], a[0]) : Long.compare(b[2], a[2]));
        long[] seq = {0};
        for (int k : order) {
            if (kept.size() == limit && bound[k] > kept.peek()[0]) {
                break;
            }
            visitSection(keys[k], sections.get(keys[k]), (x, y, z, seen) -> {
                long dx = x - from.getX(), dy = y - from.getY(), dz = z - from.getZ();
                long d = dx * dx + dy * dy + dz * dz;
                if (kept.size() < limit) {
                    kept.add(new long[]{d, BlockPos.asLong(x, y, z), seq[0]++});
                } else if (d < kept.peek()[0]) {
                    kept.poll();
                    kept.add(new long[]{d, BlockPos.asLong(x, y, z), seq[0]++});
                }
            });
        }
        List<long[]> sorted = new ArrayList<>(kept);
        sorted.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[2], b[2]));
        List<BlockPos> out = new ArrayList<>(sorted.size());
        for (long[] e : sorted) {
            out.add(BlockPos.of(e[1]));
        }
        return out;
    }

    /** 离所有格子的平均位置最近的那一格:落在这堆格子里,环形、L 形的也一样。空的是 null。 */
    public BlockPos center() {
        if (isEmpty()) {
            return null;
        }
        double[] sum = new double[3];
        forEach((x, y, z, seen) -> {
            sum[0] += x;
            sum[1] += y;
            sum[2] += z;
        });
        return nearest(sum[0] / size, sum[1] / size, sum[2] / size);
    }

    // ==================== 运算 ====================

    /** 并:在任一边的格;两边都附带方块的留较新的。 */
    public Cells union(Cells other) {
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>(sections);
        for (Long2ObjectMap.Entry<Section> e : other.sections.long2ObjectEntrySet()) {
            Section a = sections.get(e.getLongKey());
            Section b = e.getValue();
            out.put(e.getLongKey(), a == null ? b : merge(or(a.bits, b.bits), a, b));
        }
        return new Cells(out);
    }

    /** 差:在这边、不在那边的格;附带的方块照这边的。 */
    public Cells minus(Cells other) {
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Section> e : sections.long2ObjectEntrySet()) {
            Section a = e.getValue();
            Section b = other.sections.get(e.getLongKey());
            put(out, e.getLongKey(), b == null ? a : merge(andNot(a.bits, b.bits), a, null));
        }
        return new Cells(out);
    }

    /** 交:两边都有的格;两边都附带方块的留较新的。 */
    public Cells intersect(Cells other) {
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Section> e : sections.long2ObjectEntrySet()) {
            Section b = other.sections.get(e.getLongKey());
            if (b != null) {
                Section a = e.getValue();
                put(out, e.getLongKey(), merge(and(a.bits, b.bits), a, b));
            }
        }
        return new Cells(out);
    }

    /**
     * 按方块筛:留下附带的方块合 {@code test} 的格。谓词只作用在附带的方块上,这里不读世界;没附带方块的格(框出来的)
     * 不知道里面是什么,一律不留——"是不是原木"答不上来就不算是。要按世界里现在的样子筛框出来的格,先由读世界的一方
     * 把方块附上(扫描、复核)再筛。
     */
    public Cells filter(Predicate<BlockState> test) {
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<Section> e : sections.long2ObjectEntrySet()) {
            Section a = e.getValue();
            if (a.seen == null) {
                continue;
            }
            boolean[] keep = new boolean[a.palette.length];
            for (int n = 0; n < keep.length; n++) {
                keep[n] = test.test(a.palette[n].state());
            }
            long[] bits = new long[LONGS];
            for (int i = 0; i < CELLS; i++) {
                int n = a.seen[i];
                if (n != 0 && keep[n - 1] && a.has(i)) {
                    bits[i >>> 6] |= 1L << (i & 63);
                }
            }
            put(out, e.getLongKey(), merge(bits, a, null));
        }
        return new Cells(out);
    }

    /**
     * 外扩 N 格:每一格换成以它为中心、边长 {@code 2N+1} 的立方体(六个方向连对角都扩),新扩进来的格不附带方块。
     * 立方体可以沿三个轴分开做,每个轴按行平移后取并,半径按 1、2、4…… 翻倍地攒,N 格只要 log N 轮。
     */
    public Cells grow(int n) {
        if (n < 0) {
            throw new IllegalArgumentException("grow by 0 or more cells, got " + n);
        }
        if (n == 0 || isEmpty()) {
            return this;
        }
        Long2ObjectOpenHashMap<long[]> bits = new Long2ObjectOpenHashMap<>(sections.size());
        for (Long2ObjectMap.Entry<Section> e : sections.long2ObjectEntrySet()) {
            bits.put(e.getLongKey(), e.getValue().bits);
        }
        for (int axis = 0; axis < 3; axis++) {
            int reach = 0;
            while (reach < n) {
                int step = Math.min(reach + 1, n - reach);
                Long2ObjectOpenHashMap<long[]> next = copy(bits);
                orInto(next, shifted(bits, axis == 0 ? step : 0, axis == 1 ? step : 0, axis == 2 ? step : 0));
                orInto(next, shifted(bits, axis == 0 ? -step : 0, axis == 1 ? -step : 0, axis == 2 ? -step : 0));
                bits = next;
                reach += step;
            }
        }
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>();
        for (Long2ObjectMap.Entry<long[]> e : bits.long2ObjectEntrySet()) {
            put(out, e.getLongKey(), merge(e.getValue(), sections.get(e.getLongKey()), null));
        }
        return new Cells(out);
    }

    // ==================== 存盘 ====================

    /** 存成 NBT:每节存键与位图,有附带方块的节另存调色板与各格下标(按位图里的格序,只存在里面的格)。 */
    public CompoundTag save() {
        ListTag list = new ListTag();
        for (long key : keys) {
            Section s = sections.get(key);
            CompoundTag t = new CompoundTag();
            t.putLong("pos", key);
            t.putLongArray("bits", s.bits);
            if (s.seen != null) {
                ListTag palette = new ListTag();
                for (Seen seen : s.palette) {
                    CompoundTag p = new CompoundTag();
                    p.put("state", NbtUtils.writeBlockState(seen.state()));
                    p.putLong("tick", seen.tick());
                    palette.add(p);
                }
                t.put("palette", palette);
                int[] seenIdx = new int[s.count];
                int j = 0;
                for (int i = 0; i < CELLS; i++) {
                    if (s.has(i)) {
                        seenIdx[j++] = s.seen[i];
                    }
                }
                t.putIntArray("seen", seenIdx);
            }
            list.add(t);
        }
        CompoundTag out = new CompoundTag();
        out.put("sections", list);
        return out;
    }

    /**
     * 读回 {@link #save} 存的样子。
     *
     * @throws IllegalArgumentException 位图不是 64 个 long,或下标对不上调色板
     */
    public static Cells load(CompoundTag tag, HolderGetter<Block> blocks) {
        Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>();
        ListTag list = tag.getList("sections", Tag.TAG_COMPOUND);
        for (int k = 0; k < list.size(); k++) {
            CompoundTag t = list.getCompound(k);
            long[] bits = t.getLongArray("bits");
            if (bits.length != LONGS) {
                throw new IllegalArgumentException("a section holds " + LONGS + " longs, this one " + bits.length);
            }
            Seen[] palette = null;
            short[] seen = null;
            int count = count(bits);
            if (t.contains("palette", Tag.TAG_LIST)) {
                ListTag p = t.getList("palette", Tag.TAG_COMPOUND);
                palette = new Seen[p.size()];
                for (int n = 0; n < palette.length; n++) {
                    CompoundTag e = p.getCompound(n);
                    palette[n] = new Seen(NbtUtils.readBlockState(blocks, e.getCompound("state")), e.getLong("tick"));
                }
                int[] seenIdx = t.getIntArray("seen");
                if (seenIdx.length != count) {
                    throw new IllegalArgumentException("a section has " + count + " cells but " + seenIdx.length
                            + " palette entries");
                }
                seen = new short[CELLS];
                int j = 0;
                for (int i = 0; i < CELLS; i++) {
                    if ((bits[i >>> 6] >>> (i & 63) & 1L) != 0) {
                        int n = seenIdx[j++];
                        if (n < 0 || n > palette.length) {
                            throw new IllegalArgumentException("palette index " + n + " out of " + palette.length);
                        }
                        seen[i] = (short) n;
                    }
                }
            }
            if (count > 0) {
                out.put(t.getLong("pos"), new Section(bits, palette, seen, count));
            }
        }
        return new Cells(out);
    }

    // ==================== 值语义 ====================

    /** 同样的格子、每格附带同样的方块。 */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Cells other) || other.size != size || !Arrays.equals(other.keys, keys)) {
            return false;
        }
        for (long key : keys) {
            Section a = sections.get(key);
            Section b = other.sections.get(key);
            if (!Arrays.equals(a.bits, b.bits)) {
                return false;
            }
            if (a.seen == null && b.seen == null) {
                continue;
            }
            for (int i = 0; i < CELLS; i++) {
                if (a.has(i) && !java.util.Objects.equals(a.seenAt(i), b.seenAt(i))) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(size) * 31 + Arrays.hashCode(keys);
    }

    @Override
    public String toString() {
        return size + " cells";
    }

    // ==================== 节与行 ====================

    private static long key(int x, int y, int z) {
        return SectionPos.asLong(x >> 4, y >> 4, z >> 4);
    }

    private static int index(int x, int y, int z) {
        return (y & 15) << 8 | (z & 15) << 4 | (x & 15);
    }

    private static int count(long[] bits) {
        int n = 0;
        for (long w : bits) {
            n += Long.bitCount(w);
        }
        return n;
    }

    private static void visitSection(long key, Section s, Visitor visitor) {
        int ox = SectionPos.sectionToBlockCoord(SectionPos.x(key));
        int oy = SectionPos.sectionToBlockCoord(SectionPos.y(key));
        int oz = SectionPos.sectionToBlockCoord(SectionPos.z(key));
        for (int w = 0; w < LONGS; w++) {
            long word = s.bits[w];
            while (word != 0) {
                int i = (w << 6) | Long.numberOfTrailingZeros(word);
                visitor.visit(ox + (i & 15), oy + (i >>> 8), oz + (i >>> 4 & 15), s.seenAt(i));
                word &= word - 1;
            }
        }
    }

    /** 这一节离点 {@code (px, py, pz)} 最近可能的距离平方。 */
    private static double boxDistSqr(long key, double px, double py, double pz) {
        double dx = gap(SectionPos.sectionToBlockCoord(SectionPos.x(key)), px);
        double dy = gap(SectionPos.sectionToBlockCoord(SectionPos.y(key)), py);
        double dz = gap(SectionPos.sectionToBlockCoord(SectionPos.z(key)), pz);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double gap(int lo, double p) {
        return Math.max(0, Math.max(lo - p, p - (lo + 15)));
    }

    /**
     * 位图 {@code bits} 的格子各自附带什么:两边都附带的留较新的(一样新留 {@code a} 的),只一边附带的照那边。位图全空
     * 是 null(这一节不存)。位图与 {@code a} 一样、{@code b} 又没附带方块时就是 {@code a} 本身。
     */
    private static Section merge(long[] bits, Section a, Section b) {
        int count = count(bits);
        if (count == 0) {
            return null;
        }
        boolean aSeen = a != null && a.seen != null;
        boolean bSeen = b != null && b.seen != null;
        if (a != null && (b == null || !bSeen) && Arrays.equals(bits, a.bits)) {
            return a;
        }
        if (!aSeen && !bSeen) {
            return new Section(bits, null, null, count);
        }
        List<Seen> palette = new ArrayList<>();
        Map<Seen, Integer> slot = new HashMap<>();
        short[] seen = new short[CELLS];
        for (int w = 0; w < LONGS; w++) {
            long word = bits[w];
            while (word != 0) {
                int i = (w << 6) | Long.numberOfTrailingZeros(word);
                Seen x = aSeen ? a.seenAt(i) : null;
                Seen y = bSeen ? b.seenAt(i) : null;
                Seen keep = x == null ? y : y == null || y.tick() <= x.tick() ? x : y;
                if (keep != null) {
                    seen[i] = (short) (int) slot.computeIfAbsent(keep, k -> {
                        palette.add(k);
                        return palette.size();
                    });
                }
                word &= word - 1;
            }
        }
        return palette.isEmpty() ? new Section(bits, null, null, count)
                : new Section(bits, palette.toArray(Seen[]::new), seen, count);
    }

    private static void put(Long2ObjectOpenHashMap<Section> out, long key, Section s) {
        if (s != null) {
            out.put(key, s);
        }
    }

    private static long[] or(long[] a, long[] b) {
        long[] out = new long[LONGS];
        for (int w = 0; w < LONGS; w++) {
            out[w] = a[w] | b[w];
        }
        return out;
    }

    private static long[] and(long[] a, long[] b) {
        long[] out = new long[LONGS];
        for (int w = 0; w < LONGS; w++) {
            out[w] = a[w] & b[w];
        }
        return out;
    }

    private static long[] andNot(long[] a, long[] b) {
        long[] out = new long[LONGS];
        for (int w = 0; w < LONGS; w++) {
            out[w] = a[w] & ~b[w];
        }
        return out;
    }

    private static Long2ObjectOpenHashMap<long[]> copy(Long2ObjectOpenHashMap<long[]> bits) {
        Long2ObjectOpenHashMap<long[]> out = new Long2ObjectOpenHashMap<>(bits.size());
        for (Long2ObjectMap.Entry<long[]> e : bits.long2ObjectEntrySet()) {
            out.put(e.getLongKey(), e.getValue().clone());
        }
        return out;
    }

    private static void orInto(Long2ObjectOpenHashMap<long[]> into, Long2ObjectOpenHashMap<long[]> from) {
        for (Long2ObjectMap.Entry<long[]> e : from.long2ObjectEntrySet()) {
            long[] dst = into.computeIfAbsent(e.getLongKey(), k -> new long[LONGS]);
            long[] src = e.getValue();
            for (int w = 0; w < LONGS; w++) {
                dst[w] |= src[w];
            }
        }
    }

    private static void orLane(Long2ObjectOpenHashMap<long[]> into, long key, int lane, int row) {
        into.computeIfAbsent(key, k -> new long[LONGS])[lane >>> 2] |= (long) row << ((lane & 3) << 4);
    }

    /**
     * 整堆格子平移 {@code (dx, dy, dz)}:一行一行搬。y、z 方向整行换个位置;x 方向行内左移,移出本节的那几位落进
     * 下一节的同一行。
     */
    private static Long2ObjectOpenHashMap<long[]> shifted(Long2ObjectOpenHashMap<long[]> bits, int dx, int dy, int dz) {
        Long2ObjectOpenHashMap<long[]> out = new Long2ObjectOpenHashMap<>();
        int qx = Math.floorDiv(dx, 16);
        int rx = Math.floorMod(dx, 16);
        for (Long2ObjectMap.Entry<long[]> e : bits.long2ObjectEntrySet()) {
            long key = e.getLongKey();
            long[] src = e.getValue();
            int sx = SectionPos.x(key);
            int sy = SectionPos.y(key);
            int sz = SectionPos.z(key);
            for (int lane = 0; lane < LANES; lane++) {
                int row = (int) (src[lane >>> 2] >>> ((lane & 3) << 4)) & 0xFFFF;
                if (row == 0) {
                    continue;
                }
                int gy = (lane >>> 4) + dy;
                int gz = (lane & 15) + dz;
                int ty = sy + Math.floorDiv(gy, 16);
                int tz = sz + Math.floorDiv(gz, 16);
                int toLane = Math.floorMod(gy, 16) << 4 | Math.floorMod(gz, 16);
                int stay = (row << rx) & 0xFFFF;
                if (stay != 0) {
                    orLane(out, SectionPos.asLong(sx + qx, ty, tz), toLane, stay);
                }
                int over = rx == 0 ? 0 : row >>> (16 - rx);
                if (over != 0) {
                    orLane(out, SectionPos.asLong(sx + qx + 1, ty, tz), toLane, over);
                }
            }
        }
        return out;
    }

    /** 攒格子:位图按节开,附带的方块先按格记,出货时每节压成调色板。 */
    private static final class Builder {
        private final Long2ObjectOpenHashMap<long[]> bits = new Long2ObjectOpenHashMap<>();
        private final Long2ObjectOpenHashMap<Seen[]> seen = new Long2ObjectOpenHashMap<>();

        void add(int x, int y, int z, Seen s) {
            long key = key(x, y, z);
            int i = index(x, y, z);
            bits.computeIfAbsent(key, k -> new long[LONGS])[i >>> 6] |= 1L << (i & 63);
            if (s != null) {
                seen.computeIfAbsent(key, k -> new Seen[CELLS])[i] = s;
            }
        }

        /** 第 {@code (y, z)} 行从 {@code x0} 到 {@code x1}(含)的格:每节一次按位或。 */
        void row(int y, int z, int x0, int x1) {
            int lane = (y & 15) << 4 | (z & 15);
            for (int sx = x0 >> 4; sx <= x1 >> 4; sx++) {
                int lo = Math.max(x0, sx << 4) & 15;
                int hi = Math.min(x1, (sx << 4) + 15) & 15;
                orLane(bits, SectionPos.asLong(sx, y >> 4, z >> 4), lane, ((1 << (hi - lo + 1)) - 1) << lo);
            }
        }

        Cells build() {
            Long2ObjectOpenHashMap<Section> out = new Long2ObjectOpenHashMap<>(bits.size());
            for (Long2ObjectMap.Entry<long[]> e : bits.long2ObjectEntrySet()) {
                long[] b = e.getValue();
                Seen[] cells = seen.get(e.getLongKey());
                Section s = new Section(b, null, null, count(b));
                if (cells != null) {
                    List<Seen> palette = new ArrayList<>();
                    Map<Seen, Integer> slot = new HashMap<>();
                    short[] idx = new short[CELLS];
                    for (int i = 0; i < CELLS; i++) {
                        if (cells[i] != null) {
                            idx[i] = (short) (int) slot.computeIfAbsent(cells[i], k -> {
                                palette.add(k);
                                return palette.size();
                            });
                        }
                    }
                    s = new Section(b, palette.toArray(Seen[]::new), idx, s.count);
                }
                out.put(e.getLongKey(), s);
            }
            return new Cells(out);
        }
    }
}
