package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.EntityRef;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.permission.Listing;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;

/**
 * 路线描述里一处去处的"哪儿":一格、一列、一个高度、一只实体,或一堆格子(一团矿、一片格子——任何带 {@code cells} 的表)。
 * 写法只在 {@link #read} 认,格子、一处与实体的读法借 {@link ArgType} 的那一份;怎样算到了是 {@link Stop} 的事。
 */
public sealed interface Target {

    /** 脚本里它的样子。 */
    ScriptType SCRIPT = ScriptType.union(Shapes.POS.type(), Shapes.BLOCK.type(), Shapes.ENTITY.type(),
            ScriptType.table(ScriptType.field("cells", ScriptType.listOf(Shapes.POS.type()), "Cells, e.g. a Cluster.")),
            ScriptType.table(ScriptType.field("x", ScriptType.NUMBER, null), ScriptType.field("z", ScriptType.NUMBER, null)),
            ScriptType.table(ScriptType.field("y", ScriptType.NUMBER, null)));

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

    /** 一堆格子(带 {@code cells} 的表,如扫描出的一团矿),至少一格。 */
    record Cells(List<BlockPos> cells) implements Target {

        public Cells {
            if (cells.isEmpty()) {
                throw new IllegalArgumentException("cells is empty: there is no cell to go to");
            }
            cells = cells.stream().map(BlockPos::immutable).toList();
        }
    }

    /**
     * 脚本给的一处读成去处。
     *
     * @throws IllegalArgumentException 写不成一处:说要什么样子、给了什么
     */
    static Target read(JsonElement value) {
        if (value != null && value.isJsonObject()) {
            JsonObject o = value.getAsJsonObject();
            if (o.get("cells") instanceof JsonArray cells) {
                List<BlockPos> out = new ArrayList<>(cells.size());
                cells.forEach(c -> out.add(ArgType.cellOf(c)));
                return new Cells(out);
            }
            if (o.has("id") && !o.has("x")) {
                return new Mob(ArgType.entityOf(o));
            }
        }
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return new Mob(ArgType.entityOf(value));
        }
        if (value != null && value.isJsonPrimitive()) {
            throw new IllegalArgumentException("a place is a table — a Pos, a Block, an Entity, anything with cells "
                    + "(a Cluster), a column {x = …, z = …} or a height {y = …}; got \"" + value.getAsString() + "\"");
        }
        Place place = ArgType.placeOf(value);
        if (place.cell() != null) {
            return new Cell(place.cell());
        }
        return place.x() != null ? new Column(place.x(), place.z()) : new Height(place.y());
    }

    /** 写回脚本给的样子(JSON):{@link #read} 读回来是同一处。 */
    default JsonElement json() {
        return switch (this) {
            case Cell c -> Shapes.pos(c.pos());
            case Column c -> {
                JsonObject o = new JsonObject();
                o.addProperty("x", c.x());
                o.addProperty("z", c.z());
                yield o;
            }
            case Height h -> {
                JsonObject o = new JsonObject();
                o.addProperty("y", h.y());
                yield o;
            }
            case Mob m -> {
                JsonObject o = new JsonObject();
                if (m.ref().id() != null) {
                    o.addProperty("id", m.ref().id());
                } else {
                    o.addProperty("id", m.ref().uuid().toString());
                }
                yield o;
            }
            case Cells c -> {
                JsonArray cells = new JsonArray();
                c.cells().forEach(p -> cells.add(Shapes.pos(p)));
                JsonObject o = new JsonObject();
                o.add("cells", cells);
                yield o;
            }
        };
    }

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
