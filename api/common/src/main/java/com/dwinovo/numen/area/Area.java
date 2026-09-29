package com.dwinovo.numen.area;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一块区域:一个维度里的一堆格子({@link Cells}),由若干<b>部分</b>组成——扫描出的每一团、每次框的盒子、每个点各是一部分。
 * 整块区域是各部分的并。不可变值,任何线程可读;存在主人名下由 {@link AreaStore} 管。
 *
 * <h2>部分的编号</h2>
 * 一个字母({@link Kind#letter})加一个数:{@code g1}、{@code b2}、{@code p1}。每种字母在这块区域里各自往上数,
 * 删了一部分不重排、号也不再发:{@code ores/g3} 一直指同一团,删掉以后再点它就是"没有这一部分",不会悄悄指到别的团。
 *
 * <h2>运算</h2>
 * 结果当场算出、是一块新的区域,不存算式。左边的各部分逐个与右边的<b>整块</b>运算,编号照旧(空了的部分去掉,号不回收):
 * {@code house minus house/b2} 之后 {@code b1}、{@code b3} 还叫原来的名字。并是把右边的各部分接在左边后面,按左边的
 * 计数续号。两边不在同一个维度就报错:一块区域只属于一个维度。
 */
public final class Area {

    /** 一部分是怎么来的,决定编号的字母。 */
    public enum Kind {
        /** 扫描出的一团,格子附带当时看到的方块。 */
        GROUP('g'),
        /** 框的一个盒子。 */
        BOX('b'),
        /** 一个点。 */
        POINT('p'),
        /** 一个球(以一格为中心、给定半径)。 */
        SPHERE('s');

        private final char letter;

        Kind(char letter) {
            this.letter = letter;
        }

        public char letter() {
            return letter;
        }

        static Kind byLetter(char c) {
            for (Kind k : values()) {
                if (k.letter == c) {
                    return k;
                }
            }
            return null;
        }
    }

    /** 一部分:编号与格子。 */
    public record Part(String id, Cells cells) {
    }

    private static final Pattern PART_ID = Pattern.compile("([gbps])([1-9][0-9]{0,8})");

    private final ResourceKey<Level> dimension;
    private final List<Part> parts;
    /** 每种字母发到第几号了(发过的最大号);删了的部分的号不再发。 */
    private final Map<Kind, Integer> issued;
    /** 各部分的并;构造时算好,查"一格在不在整块区域里"只查它。 */
    private final Cells whole;

    private Area(ResourceKey<Level> dimension, List<Part> parts, Map<Kind, Integer> issued, Cells whole) {
        this.dimension = dimension;
        this.parts = List.copyOf(parts);
        EnumMap<Kind, Integer> counts = new EnumMap<>(Kind.class);
        counts.putAll(issued);
        this.issued = counts;
        this.whole = whole;
    }

    private Area(ResourceKey<Level> dimension, List<Part> parts, Map<Kind, Integer> issued) {
        this(dimension, parts, issued, unionOf(parts));
    }

    private static Cells unionOf(List<Part> parts) {
        if (parts.isEmpty()) {
            return Cells.EMPTY;
        }
        Cells all = parts.get(0).cells();
        for (int i = 1; i < parts.size(); i++) {
            all = all.union(parts.get(i).cells());
        }
        return all;
    }

    /** 这个维度里一块还没有部分的区域。 */
    public static Area empty(ResourceKey<Level> dimension) {
        return new Area(dimension, List.of(), Map.of());
    }

    /** 只有一部分的区域:{@code of(dim, Kind.BOX, Cells.box(a, b))} 就是一个框出来的盒子,那一部分叫 {@code b1}。 */
    public static Area of(ResourceKey<Level> dimension, Kind kind, Cells cells) {
        return empty(dimension).with(kind, cells);
    }

    /** 编号合不合规矩({@code g3}、{@code b12})。 */
    public static boolean isPartId(String id) {
        return id != null && PART_ID.matcher(id).matches();
    }

    // ==================== 查询 ====================

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    /** 各部分,按加进来的先后。 */
    public List<Part> parts() {
        return parts;
    }

    /** 编号是 {@code id} 的那一部分;没有是 null。 */
    public Part part(String id) {
        for (Part p : parts) {
            if (p.id().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** 整块区域的格子(各部分的并)。格数、包围盒、逐格遍历、最近一格都在它上面问。 */
    public Cells cells() {
        return whole;
    }

    /** 这一格在不在这块区域里:同一个维度、落在某一部分里。 */
    public boolean contains(ResourceKey<Level> dimension, BlockPos pos) {
        return this.dimension.equals(dimension) && whole.contains(pos);
    }

    // ==================== 改部分 ====================

    /**
     * 加一部分,编号按这种字母的计数续下去。
     *
     * @throws IllegalArgumentException 格子是空的:一部分至少有一格
     */
    public Area with(Kind kind, Cells cells) {
        if (cells.isEmpty()) {
            throw new IllegalArgumentException("a part of an area has at least one cell");
        }
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        counts.putAll(issued);
        int n = counts.getOrDefault(kind, 0) + 1;
        counts.put(kind, n);
        List<Part> next = new ArrayList<>(parts);
        next.add(new Part(String.valueOf(kind.letter) + n, cells));
        return new Area(dimension, next, counts, whole.union(cells));
    }

    /**
     * 去掉一部分;别的部分编号不变,这个号以后也不再发。
     *
     * @throws IllegalArgumentException 没有这一部分
     */
    public Area without(String id) {
        requirePart(id);
        List<Part> next = new ArrayList<>(parts);
        next.removeIf(p -> p.id().equals(id));
        return new Area(dimension, next, issued);
    }

    /**
     * 只剩这一部分的区域(编号与计数不变):{@code ores/g3} 解出来的就是它,凡是收区域的地方都能收一部分。
     *
     * @throws IllegalArgumentException 没有这一部分
     */
    public Area only(String id) {
        Part p = requirePart(id);
        return new Area(dimension, List.of(p), issued, p.cells());
    }

    // ==================== 运算 ====================

    /** 并:左边的各部分照旧,右边的各部分接在后面、按左边的计数续号。 */
    public Area union(Area other) {
        requireSameDimension(other);
        Area out = this;
        for (Part p : other.parts) {
            out = out.with(kindOf(p.id()), p.cells());
        }
        return out;
    }

    /** 差:左边的每一部分去掉右边整块区域里的格。 */
    public Area minus(Area other) {
        requireSameDimension(other);
        return eachPart(c -> c.minus(other.whole));
    }

    /** 交:左边的每一部分只留右边整块区域里也有的格。 */
    public Area intersect(Area other) {
        requireSameDimension(other);
        return eachPart(c -> c.intersect(other.whole));
    }

    /** 按方块筛:每一部分留下附带的方块合 {@code test} 的格;没附带方块的格怎么算见 {@link Cells#filter}。 */
    public Area filter(Predicate<BlockState> test) {
        return eachPart(c -> c.filter(test));
    }

    /** 外扩 N 格:每一部分各自外扩(见 {@link Cells#grow}),整块就是整块外扩。 */
    public Area grow(int n) {
        return eachPart(c -> c.grow(n));
    }

    /**
     * 中心附近的一格:整块区域里离所有格子的平均位置最近的那一格,成一块只有一个点({@code p1})的新区域。
     *
     * @throws IllegalArgumentException 区域是空的
     */
    public Area center() {
        BlockPos at = whole.center();
        if (at == null) {
            throw new IllegalArgumentException("an empty area has no center");
        }
        return of(dimension, Kind.POINT, whole.intersect(Cells.point(at)));
    }

    // ==================== 存盘 ====================

    public CompoundTag save() {
        CompoundTag out = new CompoundTag();
        out.putString("dimension", dimension.location().toString());
        CompoundTag counts = new CompoundTag();
        issued.forEach((kind, n) -> counts.putInt(String.valueOf(kind.letter), n));
        out.put("issued", counts);
        ListTag list = new ListTag();
        for (Part p : parts) {
            CompoundTag t = new CompoundTag();
            t.putString("id", p.id());
            t.put("cells", p.cells().save());
            list.add(t);
        }
        out.put("parts", list);
        return out;
    }

    /**
     * 读回 {@link #save} 存的样子。
     *
     * @throws IllegalArgumentException 维度、编号或格子读不通
     */
    public static Area load(CompoundTag tag, HolderGetter<Block> blocks) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
        if (dim == null) {
            throw new IllegalArgumentException("bad dimension '" + tag.getString("dimension") + "'");
        }
        Map<Kind, Integer> counts = new EnumMap<>(Kind.class);
        CompoundTag issuedTag = tag.getCompound("issued");
        for (Kind kind : Kind.values()) {
            String letter = String.valueOf(kind.letter);
            if (issuedTag.contains(letter, Tag.TAG_INT)) {
                counts.put(kind, issuedTag.getInt(letter));
            }
        }
        List<Part> parts = new ArrayList<>();
        ListTag list = tag.getList("parts", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            String id = t.getString("id");
            if (!isPartId(id)) {
                throw new IllegalArgumentException("bad part id '" + id + "'");
            }
            parts.add(new Part(id, Cells.load(t.getCompound("cells"), blocks)));
        }
        return new Area(ResourceKey.create(Registries.DIMENSION, dim), parts, counts);
    }

    // ==================== 值语义 ====================

    @Override
    public boolean equals(Object o) {
        return o instanceof Area a && a.dimension.equals(dimension) && a.parts.equals(parts)
                && a.issued.equals(issued);
    }

    @Override
    public int hashCode() {
        return dimension.hashCode() * 31 + parts.hashCode();
    }

    @Override
    public String toString() {
        return parts.size() + " parts, " + whole.size() + " cells in " + dimension.location();
    }

    private Area eachPart(Function<Cells, Cells> op) {
        List<Part> next = new ArrayList<>();
        for (Part p : parts) {
            Cells c = op.apply(p.cells());
            if (!c.isEmpty()) {
                next.add(new Part(p.id(), c));
            }
        }
        return new Area(dimension, next, issued);
    }

    private Part requirePart(String id) {
        Part p = part(id);
        if (p == null) {
            throw new IllegalArgumentException("no part " + id + "; the parts are "
                    + (parts.isEmpty() ? "none" : String.join(", ", parts.stream().map(Part::id).toList())));
        }
        return p;
    }

    private void requireSameDimension(Area other) {
        if (!dimension.equals(other.dimension)) {
            throw new IllegalArgumentException("the areas are in different dimensions (" + dimension.location()
                    + " and " + other.dimension.location() + "); an area belongs to one dimension");
        }
    }

    private static Kind kindOf(String id) {
        Matcher m = PART_ID.matcher(id);
        if (!m.matches()) {
            throw new IllegalStateException("bad part id " + id);
        }
        return Kind.byLetter(m.group(1).charAt(0));
    }
}
