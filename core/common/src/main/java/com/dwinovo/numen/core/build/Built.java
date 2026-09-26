package com.dwinovo.numen.core.build;

import com.dwinovo.numen.core.Constants;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 建成的房子,跟着世界存档走(主世界的 SavedData,一份记全部维度):每一栋是某份设计或蓝图文件在某个维度、某个落点上
 * 盖出来的那一次,记着她<b>实际放下的每一格</b>(格 → 方块)。
 *
 * <p>这份记录是"这栋房子由哪些格子组成"的唯一出处。再按同一份施工图在同一个落点 {@code build at},设计里已经没有、
 * 记录里有、世界里现在仍是记录里那个方块的格子,就是该拆的——别人放的、主人后来自己摆的、被改动过的,不在记录里或
 * 已对不上,一律不碰({@link Changes})。所以设计只存现状,不存版本。
 *
 * <p>记录随施工逐格写({@link #placed}、{@link #cleared}):放下一格记一格、拆掉一格划一格。活怎么收场、服务器停在哪一刻,
 * 记下的都是世界里真发生的,不靠收工时补账。
 */
public final class Built extends SavedData {

    private static final String KEY = "numen_built";
    private static final String BUILDINGS = "buildings";

    private static final SavedData.Factory<Built> FACTORY = new SavedData.Factory<>(
            Built::new, Built::load, DataFixTypes.SAVED_DATA_RANDOM_SEQUENCES);

    /**
     * 一次施工对着的那一栋:照哪份施工图(设计名或蓝图文件名)、在哪个维度、原点落在哪——这三样认一栋房子;
     * {@code quarters} 是这一次朝哪儿转,朝向不同也还是同一栋。
     */
    public record Site(String source, ResourceLocation dimension, BlockPos anchor, int quarters) {

        public Site {
            anchor = anchor.immutable();
        }

        /** 和 {@code building} 是不是同一栋:同一份施工图、同一个维度、同一个落点。 */
        boolean is(Building building) {
            return building.source.equals(source) && building.dimension.equals(dimension)
                    && building.anchor.equals(anchor);
        }
    }

    /** 建成的一栋。 */
    public static final class Building {

        private final String source;
        private final int number;
        private final ResourceLocation dimension;
        private final BlockPos anchor;
        private int quarters;
        private final String builder;
        private final long builtAt;
        private long changedAt;
        /** 她放下的每一格,按放下的先后。 */
        private final Map<Long, Block> cells;

        private Building(String source, int number, ResourceLocation dimension, BlockPos anchor, int quarters,
                         String builder, long builtAt, long changedAt, Map<Long, Block> cells) {
            this.source = source;
            this.number = number;
            this.dimension = dimension;
            this.anchor = anchor.immutable();
            this.quarters = quarters;
            this.builder = builder;
            this.builtAt = builtAt;
            this.changedAt = changedAt;
            this.cells = new LinkedHashMap<>(cells);
        }

        /** {@code house#1}:施工图的名字加它在同名的几栋里排第几。 */
        public String name() {
            return source + "#" + number;
        }

        public String source() {
            return source;
        }

        public ResourceLocation dimension() {
            return dimension;
        }

        public BlockPos anchor() {
            return anchor;
        }

        /** 最近一次照施工图改它时朝哪儿转(顺时针几个 90°)。 */
        public int quarters() {
            return quarters;
        }

        /** 第一次盖它的那位同伴。 */
        public String builder() {
            return builder;
        }

        /** 第一次盖下去的那一刻(主世界游戏刻)。 */
        public long builtAt() {
            return builtAt;
        }

        /** 最近一次放下或拆掉一格的那一刻。 */
        public long changedAt() {
            return changedAt;
        }

        /** 她在这一栋放下、现在还记着的格子:格({@link BlockPos#asLong})→ 方块。 */
        public Map<Long, Block> cells() {
            return java.util.Collections.unmodifiableMap(cells);
        }

        private Stored stored() {
            List<Block> palette = new ArrayList<>();
            List<Long> at = new ArrayList<>();
            List<Integer> kind = new ArrayList<>();
            for (Map.Entry<Long, Block> e : cells.entrySet()) {
                int i = palette.indexOf(e.getValue());
                if (i < 0) {
                    i = palette.size();
                    palette.add(e.getValue());
                }
                at.add(e.getKey());
                kind.add(i);
            }
            return new Stored(source, number, dimension, anchor.asLong(), quarters, builder, builtAt, changedAt,
                    palette, at, kind);
        }
    }

    /** 落盘的样子:格子按调色板存,一栋几千格也只存几种方块的名字。 */
    private record Stored(String source, int number, ResourceLocation dimension, long anchor, int quarters,
                          String builder, long builtAt, long changedAt, List<Block> palette, List<Long> cells,
                          List<Integer> kinds) {

        static final Codec<Stored> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("source").forGetter(Stored::source),
                Codec.INT.fieldOf("number").forGetter(Stored::number),
                ResourceLocation.CODEC.fieldOf("dimension").forGetter(Stored::dimension),
                Codec.LONG.fieldOf("anchor").forGetter(Stored::anchor),
                Codec.INT.fieldOf("quarters").forGetter(Stored::quarters),
                Codec.STRING.fieldOf("builder").forGetter(Stored::builder),
                Codec.LONG.fieldOf("built_at").forGetter(Stored::builtAt),
                Codec.LONG.fieldOf("changed_at").forGetter(Stored::changedAt),
                BuiltInRegistries.BLOCK.byNameCodec().listOf().fieldOf("palette").forGetter(Stored::palette),
                Codec.LONG.listOf().fieldOf("cells").forGetter(Stored::cells),
                Codec.INT.listOf().fieldOf("kinds").forGetter(Stored::kinds)
        ).apply(i, Stored::new));

        Building building() {
            Map<Long, Block> map = new LinkedHashMap<>();
            for (int i = 0; i < cells.size(); i++) {
                map.put(cells.get(i), palette.get(kinds.get(i)));
            }
            return new Building(source, number, dimension, BlockPos.of(anchor), quarters, builder, builtAt,
                    changedAt, map);
        }
    }

    private static final Codec<List<Stored>> CODEC = Stored.CODEC.listOf();

    private final List<Building> buildings = new ArrayList<>();

    public Built() {
    }

    public static Built of(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, KEY);
    }

    public static Built load(CompoundTag tag, HolderLookup.Provider registries) {
        Built built = new Built();
        if (tag.contains(BUILDINGS)) {
            CODEC.parse(NbtOps.INSTANCE, tag.get(BUILDINGS))
                    .resultOrPartial(e -> Constants.LOG.error("[numen-build] 建成的房子读不回来: {}", e))
                    .ifPresent(list -> list.forEach(s -> built.buildings.add(s.building())));
        }
        return built;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CODEC.encodeStart(NbtOps.INSTANCE, buildings.stream().map(Building::stored).toList()).result()
                .ifPresent(list -> tag.put(BUILDINGS, list));
        return tag;
    }

    /** 建成的每一栋,按盖下去的先后。 */
    public List<Building> all() {
        return List.copyOf(buildings);
    }

    /** 这一处的那一栋;还没盖过是 null。 */
    public Building at(Site site) {
        for (Building b : buildings) {
            if (site.is(b)) {
                return b;
            }
        }
        return null;
    }

    /**
     * 她在这一栋放下了一格:记下格与方块。这一处还没有一栋,就从这一格起算一栋新的,编号接在同名的几栋之后。
     *
     * @param builder 放下它的同伴的名字(一栋新的记它为盖的人)
     * @param now     主世界游戏刻
     */
    public void placed(Site site, String builder, long now, BlockPos pos, Block block) {
        Building building = at(site);
        if (building == null) {
            int number = 1 + buildings.stream().filter(b -> b.source.equals(site.source()))
                    .mapToInt(b -> b.number).max().orElse(0);
            building = new Building(site.source(), number, site.dimension(), site.anchor(), site.quarters(),
                    builder, now, now, Map.of());
            buildings.add(building);
        }
        building.quarters = site.quarters();
        building.changedAt = now;
        building.cells.put(pos.asLong(), block);
        setDirty();
    }

    /** 她拆掉了这一栋的一格:从记录里划掉。这一处还没有一栋就没什么可划。 */
    public void cleared(Site site, long now, BlockPos pos) {
        Building building = at(site);
        if (building != null && building.cells.remove(pos.asLong()) != null) {
            building.changedAt = now;
            setDirty();
        }
    }
}
