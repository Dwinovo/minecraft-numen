package com.dwinovo.numen.sdk;

import com.dwinovo.numen.agent.script.ScriptType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 点名的一格:一个 Pos 只说哪一格,里面是什么算什么;一个 Block(查询交回的、带 {@code name} 的那张表)还说当时那里是什么方块——那一格
 * 换成了别的,就不再是她点名的那一个。
 *
 * @param cell  那一格
 * @param block 当时那里的方块;只给了位置为 null
 */
public record Target(BlockPos cell, Block block) {

    public Target {
        cell = cell.immutable();
    }

    @Override
    public String toString() {
        return LuaCodecs.literal(this);
    }

    /** 一个 Pos 或一个 Block 读成它;写回去照原样。 */
    static final Codec<Target> CODEC = new Codec<>() {
        @Override
        public ScriptType type() {
            return ScriptType.union(LuaCodecs.POS.type(), new ScriptType.Named("Block"));
        }

        @Override
        public Target decode(Object value) {
            BlockPos cell = Positions.cell(value);
            Map<String, Object> t = LuaCodecs.table(value);
            if (t == null || t.containsKey("id") || !(t.get("name") instanceof String name)) {
                return new Target(cell, null);
            }
            ResourceLocation id = ResourceLocation.tryParse(name);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                throw new BadValue("expected a Block's name to be a block id; got " + BadValue.given(value));
            }
            return new Target(cell, BuiltInRegistries.BLOCK.get(id));
        }

        @Override
        public Object encode(Target value) {
            if (value.block == null) {
                return Positions.value(value.cell);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", BuiltInRegistries.BLOCK.getKey(value.block).toString());
            out.put("pos", Positions.value(value.cell));
            return out;
        }
    };
}
