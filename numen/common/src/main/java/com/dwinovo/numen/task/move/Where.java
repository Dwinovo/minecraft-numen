package com.dwinovo.numen.task.move;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.script.ScriptType;
import com.dwinovo.numen.api.permission.Listing;
import com.dwinovo.numen.api.sdk.BadValue;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Place;
import com.dwinovo.numen.api.sdk.Positions;
import com.dwinovo.numen.api.sdk.Target;
import com.dwinovo.numen.tools.ScanOps;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

/**
 * 去哪儿:一格、一列、一个高度、一只实体,或一堆格子(扫描交回的一团 Cluster、一串格 Cells)。写法只在 {@link #CODEC} 认,格子、
 * 一串格、一处与实体的读法借 SDK 的那一份;怎样算到了是 {@link Arrival} 的事。
 *
 * <p>点名的是方块还是一处位置,决定了没说距离时走到哪儿({@link #beside}):位置能站进去就站进去,方块与实体站不进去,走到旁边。
 */
public sealed interface Where {

    /**
     * 一格:{@code {x, y, z}},或带 {@code pos} 的表(方块、掉落物)。
     *
     * @param block 点名的是一个方块(带 {@code name} 的表)时是那种方块;只说了位置为 null。写回去时带着它,所以程序里读到的与再读一遍的是同一个意思
     */
    record Cell(BlockPos pos, Block block) implements Where {

        public Cell {
            pos = pos.immutable();
        }
    }

    /** 一列 {@code {x, z}}:那一列上站得住的高度。 */
    record Column(int x, int z) implements Where {}

    /** 一个高度 {@code {y}}。 */
    record Height(int y) implements Where {}

    /** 一只实体(带 {@code id} 的表或编号):去处按编目标那一刻它所在的那一格。 */
    record Mob(EntityRef ref) implements Where {}

    /** 一堆格子(扫描交回的一团、一串格),至少一格。 */
    record Cells(List<Cell> items) implements Where {

        public Cells {
            if (items.isEmpty()) {
                throw new IllegalArgumentException("cells is empty: there is no cell to go to");
            }
            items = List.copyOf(items);
        }

        /** 只有位置的一串格。 */
        public static Cells of(List<BlockPos> positions) {
            return new Cells(positions.stream().map(p -> new Cell(p, null)).toList());
        }

        public List<BlockPos> cells() {
            return items.stream().map(Cell::pos).toList();
        }

        /** 点名的都是方块(一团、或一串带 {@code name} 的表),不是一串位置。 */
        public boolean blocks() {
            return items.stream().allMatch(c -> c.block() != null);
        }
    }

    /** 没说距离时走到旁边:点名的是方块或实体;位置、一列、一个高度能站进去就站进去。 */
    default boolean beside() {
        return switch (this) {
            case Cell c -> c.block != null;
            case Mob m -> true;
            case Cells c -> c.blocks();
            case Column c -> false;
            case Height h -> false;
        };
    }

    /** 一团、一串表是一堆格子;带 {@code id} 没有坐标的表或一个数是一只实体;别的是一处(一格、一列、一个高度)。写回去照原样。 */
    @SuppressWarnings("unchecked")
    Codec<Where> CODEC = new Codec<>() {
        private final Codec<EntityRef> entity = (Codec<EntityRef>) LuaCodecs.of(EntityRef.class, "numen");
        private final Codec<Place> place = (Codec<Place>) LuaCodecs.of(Place.class, "numen");
        private final Codec<Target> target = (Codec<Target>) LuaCodecs.of(Target.class, "numen");

        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.POS.type(), new ScriptType.Named("Block"), new ScriptType.Named("Entity"),
                    LuaCodecs.of(ScanOps.Cluster.class, "numen").type(), LuaCodecs.CELLS.type(),
                    ScriptType.table(ScriptType.field("x", ScriptType.NUMBER, null),
                            ScriptType.field("z", ScriptType.NUMBER, null)),
                    ScriptType.table(ScriptType.field("y", ScriptType.NUMBER, null)));
        }

        @Override
        public Where decode(Object value) {
            if (Positions.clusterBlocks(value) != null
                    || value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Map.class::isInstance)) {
                List<Cell> items = new ArrayList<>();
                for (Object item : Positions.items(value)) {
                    Target one = target.decode(item);
                    items.add(new Cell(one.cell(), one.block()));
                }
                return new Cells(items);
            }
            if (value instanceof Map<?, ?> t && t.containsKey("id") && !t.containsKey("x") || value instanceof Number) {
                return new Mob(entity.decode(value));
            }
            if (value instanceof String s && Positions.coordinates(s) == null) {
                throw new BadValue("a place is a table — a Pos, a Block, an Entity, a Cluster or Cells, a column "
                        + "{x = …, z = …} or a height {y = …}; got \"" + s + "\"");
            }
            Place p = place.decode(value);
            if (p.cell() != null) {
                Target one = target.decode(value);
                return new Cell(one.cell(), one.block());
            }
            return p.x() != null ? new Column(p.x(), p.z()) : new Height(p.y());
        }

        @Override
        public Object encode(Where value) {
            return switch (value) {
                case Cell c -> target.encode(new Target(c.pos(), c.block()));
                case Column c -> {
                    Map<String, Object> o = new LinkedHashMap<>();
                    o.put("x", (long) c.x());
                    o.put("z", (long) c.z());
                    yield o;
                }
                case Height h -> Map.of("y", (long) h.y());
                case Mob m -> entity.encode(m.ref());
                case Cells c -> {
                    List<Object> out = new ArrayList<>();
                    c.items().forEach(item -> out.add(target.encode(new Target(item.pos(), item.block()))));
                    yield out;
                }
            };
        }
    };

    /** 给模型看的一截:{@code 120,64,-35}、{@code x=120 z=-35}、{@code y=64}、{@code entity 184}、{@code 12 cells}。 */
    default String words() {
        return switch (this) {
            case Cell c -> Listing.coords(c.pos());
            case Column c -> "x=" + c.x() + " z=" + c.z();
            case Height h -> "y=" + h.y();
            case Mob m -> "entity " + m.ref();
            case Cells c -> c.items().size() == 1 ? Listing.coords(c.items().get(0).pos()) : c.items().size() + " cells";
        };
    }
}
