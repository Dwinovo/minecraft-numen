package com.dwinovo.numen.core.build;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.sdk.BadValue;
import com.dwinovo.numen.sdk.Codec;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.sdk.Positions;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要盖成的样子({@code numen.build.place}、{@code numen.build.diff} 收的那一个参数):一串格(Cells,每一格是那一格要放的方块,照
 * {@code /setblock} 的写法),或一份蓝图文件摆在哪儿({@code numen.build.blueprint} 交回的那张表:文件名、原点、转了多少度)。两样恰好
 * 一样。蓝图只记在哪、怎么摆,格子留在文件里,用到时才读。
 *
 * @param cells     一串格;是蓝图时为 null
 * @param blueprint 一份蓝图;是一串格时为 null
 */
public record Design(List<Cell> cells, Blueprint blueprint) {

    public Design {
        if ((cells == null) == (blueprint == null)) {
            throw new IllegalArgumentException("cells or a blueprint, exactly one of them");
        }
        cells = cells == null ? null : List.copyOf(cells);
    }

    /**
     * 一格:在哪,放什么。
     *
     * @param block 照 {@code /setblock} 写的方块;只给了位置为 null
     */
    public record Cell(BlockPos pos, String block) {

        public Cell {
            pos = pos.immutable();
        }
    }

    /**
     * 一份蓝图文件摆在哪儿。
     *
     * @param name     文件名(不带扩展名)
     * @param origin   文件里的原点(它的最低西北角)落在哪一格
     * @param rotation 俯视顺时针转了多少度:0、90、180 或 270
     */
    public record Blueprint(String name, BlockPos origin, int rotation) {

        public Blueprint {
            origin = origin.immutable();
        }
    }

    /**
     * 带 {@code blueprint} 名字的表是一份蓝图(其余字段不看);别的是一串格:一团、一串 Block 或 Pos、或一格。写回去照原样。
     */
    public static final Codec<Design> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.CELLS.type(), new ScriptType.Named("Blueprint"));
        }

        @Override
        public Design decode(Object value) {
            if (value instanceof Map<?, ?> t && t.get("blueprint") instanceof String name) {
                Object turned = t.get("rotation");
                if (turned != null && !(turned instanceof Long)) {
                    throw new BadValue("expected a Blueprint's rotation to be 0, 90, 180 or 270; got "
                            + BadValue.given(turned));
                }
                BlockPos origin;
                try {
                    origin = Positions.cell(t.get("origin"));
                } catch (BadValue bad) {
                    throw bad.in("the Blueprint's origin");
                }
                return new Design(null, new Blueprint(name, origin, turned == null ? 0 : ((Long) turned).intValue()));
            }
            List<Cell> cells = new ArrayList<>();
            List<Object> items = Positions.items(value);
            for (int i = 0; i < items.size(); i++) {
                Object item = items.get(i);
                try {
                    cells.add(new Cell(Positions.cell(item),
                            item instanceof Map<?, ?> m && m.get("name") instanceof String block ? block : null));
                } catch (BadValue bad) {
                    throw items.size() == 1 ? bad : bad.in("item " + (i + 1));
                }
            }
            if (cells.isEmpty()) {
                throw new BadValue("expected Cells (Blocks: the block for each cell, written as /setblock takes it) "
                        + "or a Blueprint from numen.build.blueprint; got " + BadValue.given(value));
            }
            return new Design(cells, null);
        }

        @Override
        public Object encode(Design value) {
            if (value.blueprint != null) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("blueprint", value.blueprint.name());
                out.put("origin", Positions.value(value.blueprint.origin()));
                out.put("rotation", (long) value.blueprint.rotation());
                return out;
            }
            List<Object> out = new ArrayList<>();
            for (Cell cell : value.cells) {
                if (cell.block() == null) {
                    out.add(Positions.value(cell.pos()));
                } else {
                    Map<String, Object> block = new LinkedHashMap<>();
                    block.put("name", cell.block());
                    block.put("pos", Positions.value(cell.pos()));
                    out.add(block);
                }
            }
            return out;
        }
    };
}
