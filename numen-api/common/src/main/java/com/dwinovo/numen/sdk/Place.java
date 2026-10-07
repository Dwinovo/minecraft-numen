package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 点名的一处:坐标给几个算几个——三个是一格(一个 Pos,或任何带 {@code pos} 的表),{@code {x = …, z = …}} 是一列(那一列上站得住的
 * 高度),{@code {y = …}} 是一个高度。收不收一列、一个高度由用它的函数判。
 *
 * @param x 没给为 null;与 {@code z} 同给同不给
 * @param y 没给为 null
 * @param z 没给为 null
 */
public record Place(Integer x, Integer y, Integer z) {

    public Place {
        if ((x == null) != (z == null) || (x == null && y == null)) {
            throw new IllegalArgumentException("coordinates are x y z (a cell), x z (a column) or y (a height)");
        }
    }

    /** 一格。 */
    public static Place cell(BlockPos pos) {
        return new Place(pos.getX(), pos.getY(), pos.getZ());
    }

    /** 那一格(三个数都给了时);否则为 null。 */
    public BlockPos cell() {
        return x != null && y != null ? new BlockPos(x, y, z) : null;
    }

    @Override
    public String toString() {
        return LuaCodecs.literal(this);
    }

    /** 带键的表读成它,写回去给了几个写几个。 */
    static final Codec<Place> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.POS.type(),
                    ScriptType.table(ScriptType.field("x", ScriptType.NUMBER, null),
                            ScriptType.field("z", ScriptType.NUMBER, null)),
                    ScriptType.table(ScriptType.field("y", ScriptType.NUMBER, null)));
        }

        @Override
        public Place decode(Object value) {
            Map<String, Object> t = value instanceof Map<?, ?> ? LuaCodecs.table(value) : null;
            if (t != null) {
                if (t.get("pos") instanceof Map<?, ?>) {
                    return Place.cell(Positions.cell(value));
                }
                Double x = Positions.number(t, "x");
                Double y = Positions.number(t, "y");
                Double z = Positions.number(t, "z");
                if (x != null && z != null) {
                    return y != null ? Place.cell(BlockPos.containing(x, y, z))
                            : new Place((int) Math.floor(x), null, (int) Math.floor(z));
                }
                if (x == null && z == null && y != null) {
                    return new Place(null, (int) Math.floor(y), null);
                }
            }
            Map<String, Object> instead = t == null ? Positions.coordinates(value) : null;
            String shape = "expected a place: " + Positions.POS_SHAPE + ", a column {x = …, z = …} or a height "
                    + "{y = …}; got " + BadValue.given(value);
            throw instead == null ? new BadValue(shape) : new BadValue("a place given by coordinates is a table with "
                    + "named fields; got " + BadValue.given(value), instead);
        }

        @Override
        public Object encode(Place value) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (value.x != null) {
                out.put("x", (long) value.x);
            }
            if (value.y != null) {
                out.put("y", (long) value.y);
            }
            if (value.z != null) {
                out.put("z", (long) value.z);
            }
            return out;
        }
    };
}
