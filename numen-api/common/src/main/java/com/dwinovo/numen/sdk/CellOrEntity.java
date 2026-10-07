package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.core.BlockPos;

import java.util.Map;

/**
 * 一格或一只实体:看它、左键点它的函数两样都收。一个数或带 {@code id} 的表(一只 Entity)是实体,别的带位置的(Pos、Block)是一格。
 * 两样恰好一样。
 *
 * @param cell   一格;是实体时为 null
 * @param entity 一只实体;是一格时为 null
 */
public record CellOrEntity(BlockPos cell, EntityRef entity) {

    public CellOrEntity {
        if ((cell == null) == (entity == null)) {
            throw new IllegalArgumentException("a cell or an entity, exactly one of them");
        }
        cell = cell == null ? null : cell.immutable();
    }

    static final Codec<CellOrEntity> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.POS.type(), new ScriptType.Named("Entity"));
        }

        @Override
        public CellOrEntity decode(Object value) {
            boolean entity = value instanceof Number || value instanceof Map<?, ?> table && table.containsKey("id");
            return entity ? new CellOrEntity(null, EntityRef.CODEC.decode(value))
                    : new CellOrEntity(Positions.cell(value), null);
        }

        @Override
        public Object encode(CellOrEntity value) {
            return value.cell != null ? Positions.value(value.cell) : EntityRef.CODEC.encode(value.entity);
        }
    };
}
