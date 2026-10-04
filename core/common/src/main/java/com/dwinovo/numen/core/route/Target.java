package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.permission.Listing;
import com.dwinovo.numen.sdk.BadValue;
import com.dwinovo.numen.sdk.Codec;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.sdk.Place;
import com.dwinovo.numen.sdk.Positions;

import net.minecraft.core.BlockPos;

/**
 * 路线描述里一处去处的"哪儿":一格、一列、一个高度、一只实体,或一堆格子(扫描交回的一团 Cluster、一串格 Cells)。写法只在
 * {@link #CODEC} 认,格子、一串格、一处与实体的读法借 SDK 的那一份;怎样算到了是 {@link Stop} 的事。
 */
public sealed interface Target {

    /** 一格:{@code {x, y, z}},或带 {@code pos} 的表(方块、掉落物)。 */
    record Cell(BlockPos pos) implements Target {

        public Cell {
            pos = pos.immutable();
        }
    }

    /** 一列 {@code {x, z}}:那一列上站得住的高度。 */
    record Column(int x, int z) implements Target {}

    /** 一个高度 {@code {y}}。 */
    record Height(int y) implements Target {}

    /** 一只实体(带 {@code id} 的表或编号):去处按规划那一刻它在的那一格。 */
    record Mob(EntityRef ref) implements Target {}

    /** 一堆格子(扫描交回的一团、一串格),至少一格。 */
    record Cells(List<BlockPos> cells) implements Target {

        public Cells {
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("cells is empty: there is no cell to go to");
            }
            cells = cells.stream().map(BlockPos::immutable).toList();
        }
    }

    /** 一团、一串表是一堆格子;带 {@code id} 没有坐标的表或一个数是一只实体;别的是一处(一格、一列、一个高度)。写回去照原样。 */
    @SuppressWarnings("unchecked")
    Codec<Target> CODEC = new Codec<>() {
        private final Codec<EntityRef> entity = (Codec<EntityRef>) LuaCodecs.of(EntityRef.class, "numen");
        private final Codec<Place> place = (Codec<Place>) LuaCodecs.of(Place.class, "numen");

        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.POS.type(), new ScriptType.Named("Block"), new ScriptType.Named("Entity"),
                    LuaCodecs.of(ScanOps.Cluster.class, "numen").type(), LuaCodecs.CELLS.type(),
                    ScriptType.table(ScriptType.field("x", ScriptType.NUMBER, null),
                            ScriptType.field("z", ScriptType.NUMBER, null)),
                    ScriptType.table(ScriptType.field("y", ScriptType.NUMBER, null)));
        }

        @Override
        public Target decode(Object value) {
            if (Positions.clusterBlocks(value) != null
                    || value instanceof List<?> list && !list.isEmpty() && list.stream().allMatch(Map.class::isInstance)) {
                return new Cells(Positions.cells(value));
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
                return new Cell(p.cell());
            }
            return p.x() != null ? new Column(p.x(), p.z()) : new Height(p.y());
        }

        @Override
        public Object encode(Target value) {
            return switch (value) {
                case Cell c -> Positions.value(c.pos());
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
                    c.cells().forEach(p -> out.add(Positions.value(p)));
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
            case Cells c -> c.cells().size() == 1 ? Listing.coords(c.cells().get(0)) : c.cells().size() + " cells";
        };
    }
}
