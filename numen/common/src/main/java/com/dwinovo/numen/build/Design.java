package com.dwinovo.numen.build;

import com.dwinovo.numen.script.ScriptType;
import com.dwinovo.numen.api.sdk.BadValue;
import com.dwinovo.numen.api.sdk.Codec;
import com.dwinovo.numen.api.sdk.LuaCodecs;
import com.dwinovo.numen.api.sdk.Positions;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 要盖成的样子({@code numen.build.place}、{@code numen.build.diff} 收的那一个参数):一串格(Cells,每一格是那一格要放的方块,照
 * {@code /setblock} 的写法)。这里是它的值转换的落点:Lua 里的写法(一团、一串 Block 或 Pos、或一格)都读成同一串。
 *
 * @param cells 一串格,至少一格
 */
public record Design(List<Cell> cells) {

    public Design {
        cells = List.copyOf(cells);
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

    /** 一团、一串 Block 或 Pos、或一格,读成一串格;写回去照原样。 */
    public static final Codec<Design> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return LuaCodecs.CELLS.type();
        }

        @Override
        public Design decode(Object value) {
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
                throw new BadValue("expected Cells (Blocks: the block for each cell, written as /setblock takes it); "
                        + "got " + BadValue.given(value));
            }
            return new Design(cells);
        }

        @Override
        public Object encode(Design value) {
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
