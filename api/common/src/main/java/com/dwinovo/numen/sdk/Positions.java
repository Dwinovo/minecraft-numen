package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 位置的写法,只在这里读:一格是 Pos {@code {x = 120, y = 64, z = -35}},小数按 Minecraft 的定义换成所在的那一格
 * ({@code BlockPos.containing});任何带 {@code pos} 字段的表(方块、实体、掉落物、一团的 {@code nearest})放在一格的地方就是它的
 * {@code pos}。旧写法——{@code {120, 64, -35}}、{@code "120 64 -35"}、{@code "120,64,-35"}——读不成,报错带上改好的那个值。
 */
public final class Positions {

    private Positions() {}

    /** 一格的写法(给报错用)。 */
    public static final String POS_SHAPE = "a Pos {x = …, y = …, z = …} or anything with a pos (a Block, an Entity, "
            + "an Item)";

    /** {@link BlockPos}:一格。 */
    static final Codec<BlockPos> CELL = new Codec<>() {
        @Override
        public ScriptType type() {
            return LuaCodecs.POS.type();
        }

        @Override
        public BlockPos decode(Object value) {
            return cell(value);
        }

        @Override
        public Object encode(BlockPos value) {
            return value(value);
        }
    };

    /** {@link Vec3}:身体与实体的位置,两位小数。 */
    static final Codec<Vec3> EXACT = new Codec<>() {
        @Override
        public ScriptType type() {
            return LuaCodecs.POS.type();
        }

        @Override
        public Vec3 decode(Object value) {
            Map<String, Object> t = LuaCodecs.table(value);
            if (t != null && t.get("pos") != null) {
                return decode(t.get("pos"));
            }
            Double x = t == null ? null : number(t, "x");
            Double y = t == null ? null : number(t, "y");
            Double z = t == null ? null : number(t, "z");
            if (x == null || y == null || z == null) {
                throw new BadValue("expected " + POS_SHAPE + "; got " + BadValue.given(value));
            }
            return new Vec3(x, y, z);
        }

        @Override
        public Object encode(Vec3 value) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("x", truncate(value.x));
            out.put("y", truncate(value.y));
            out.put("z", truncate(value.z));
            return out;
        }
    };

    /**
     * 一格。
     *
     * @throws BadValue 不是一格:说要什么样子、给了什么(只给了一列说缺 y);写成了旧样子的坐标时带上改好的 Pos
     */
    public static BlockPos cell(Object value) {
        Map<String, Object> t = value instanceof Map<?, ?> ? LuaCodecs.table(value) : null;
        if (t != null) {
            if (t.get("pos") instanceof Map<?, ?> pos) {
                return cell(pos);
            }
            Double x = number(t, "x");
            Double y = number(t, "y");
            Double z = number(t, "z");
            if (x != null && y != null && z != null) {
                return BlockPos.containing(x, y, z);
            }
            throw new BadValue("expected " + POS_SHAPE + "; got " + BadValue.given(value)
                    + (x != null && z != null ? ", which has no y" : ""));
        }
        Object instead = coordinates(value);
        if (instead instanceof Map<?, ?> pos && pos.size() == 3) {
            throw new BadValue("a cell is a Pos with named fields; got " + BadValue.given(value), instead);
        }
        throw new BadValue("expected " + POS_SHAPE + "; got " + BadValue.given(value));
    }

    /**
     * 一串格:一团(扫描交回的 Cluster,带着它每一格的 {@code blocks})、一串 Pos 或 Block(一项是一团的也展开)、或一格。至少一格。
     *
     * @throws BadValue 读不成,或一格都没有
     */
    public static List<BlockPos> cells(Object value) {
        List<BlockPos> out = new ArrayList<>();
        List<Object> items = items(value);
        for (int i = 0; i < items.size(); i++) {
            try {
                out.add(cell(items.get(i)));
            } catch (BadValue bad) {
                throw items.size() == 1 ? bad : bad.in("item " + (i + 1));
            }
        }
        if (out.isEmpty()) {
            throw new BadValue("expected cells: a Cluster, Cells (a list of Pos or Blocks) or one cell; got "
                    + BadValue.given(value));
        }
        return out;
    }

    /** 一团的那一串格;不是一团是 null。 */
    public static List<?> clusterBlocks(Object value) {
        return value instanceof Map<?, ?> map && map.get("blocks") instanceof List<?> blocks ? blocks : null;
    }

    /** 一串格的每一项:一团展开成它的每一格,一串逐项(一项是一团的也展开),别的就是它自己那一项。 */
    public static List<Object> items(Object value) {
        List<Object> out = new ArrayList<>();
        List<?> blocks = clusterBlocks(value);
        if (blocks != null) {
            out.addAll(blocks);
        } else if (value instanceof List<?> list) {
            for (Object item : list) {
                List<?> inner = clusterBlocks(item);
                if (inner != null) {
                    out.addAll(inner);
                } else {
                    out.add(item);
                }
            }
        } else {
            out.add(value);
        }
        return out;
    }

    /** 一格写成脚本里的值:三个整数。 */
    public static Map<String, Object> value(BlockPos cell) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", (long) cell.getX());
        out.put("y", (long) cell.getY());
        out.put("z", (long) cell.getZ());
        return out;
    }

    /** 一格写成的那一段程序:{@code {x = 120, y = 64, z = -35}}。 */
    public static String literal(BlockPos cell) {
        return LuaCodecs.literal(cell);
    }

    /**
     * 一个数字段;没有或不是数是 null。
     */
    static Double number(Map<String, Object> t, String key) {
        return t.get(key) instanceof Number n ? n.doubleValue() : null;
    }

    /**
     * 往下舍到两位小数(厘米级,读得清又不冗长)。不四舍五入:原样交回收一格的参数时按所在的一格读,落在 -72.004 的掉落物入成 -72.00
     * 就成了上面一格。
     */
    static double truncate(double v) {
        return Math.floor(v * 100.0) / 100.0;
    }

    /**
     * 想写坐标却写成了别的样子(数的列表、{@code "120 64 -35"} 这样的字符串):一到三个数时,照带键的写法该写成的那张表
     * ({@code {x, y, z}}、{@code {x, z}}、{@code {y}});看不出是坐标是 null。
     */
    public static Map<String, Object> coordinates(Object value) {
        List<Long> numbers = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object e : list) {
                if (!(e instanceof Number n)) {
                    return null;
                }
                numbers.add((long) Math.floor(n.doubleValue()));
            }
        } else if (value instanceof String s) {
            String text = s.strip();
            if (!text.matches("-?\\d+(\\.\\d+)?([ ,]+-?\\d+(\\.\\d+)?)*")) {
                return null;
            }
            for (String part : text.split("[ ,]+")) {
                numbers.add((long) Math.floor(Double.parseDouble(part)));
            }
        }
        if (numbers.isEmpty() || numbers.size() > 3) {
            return null;
        }
        Map<String, Object> pos = new LinkedHashMap<>();
        switch (numbers.size()) {
            case 3 -> {
                pos.put("x", numbers.get(0));
                pos.put("y", numbers.get(1));
                pos.put("z", numbers.get(2));
            }
            case 2 -> {
                pos.put("x", numbers.get(0));
                pos.put("z", numbers.get(1));
            }
            default -> pos.put("y", numbers.get(0));
        }
        return pos;
    }
}
