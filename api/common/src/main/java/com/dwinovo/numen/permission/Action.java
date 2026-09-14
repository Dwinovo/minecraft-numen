package com.dwinovo.numen.permission;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 身体要对世界做的一件具体的事及其目标。不带工具名、不带 JSON:权限是动作的属性,
 * 哪个工具走到这里都送同一种东西。
 *
 * @param kind   动词
 * @param pos    方块动作的格子;实体动作与丢弃为 null
 * @param state  方块动作发生时那一格的方块状态(挖:要挖的;放:要被盖掉的;右键:被点的)
 * @param entity 实体动作的对象
 * @param item   放/拿/丢的物品;规划期还不知道会用哪种耗材时为 null
 */
public record Action(Kind kind, BlockPos pos, BlockState state, Entity entity, Item item) {

    /** 动词。{@link #verb} 是规则文本里写的那个词。 */
    public enum Kind {
        BREAK("break"), PLACE("place"), ATTACK("attack"), USE_BLOCK("use_block"),
        USE_ENTITY("use_entity"), TAKE("take"), DROP("drop");

        private final String verb;

        Kind(String verb) {
            this.verb = verb;
        }

        public String verb() {
            return verb;
        }

        /** 规则文本里的动词 → 动词;认不出返回 null。 */
        public static Kind byVerb(String verb) {
            for (Kind k : values()) {
                if (k.verb.equals(verb)) {
                    return k;
                }
            }
            return null;
        }
    }

    public Action {
        pos = pos == null ? null : pos.immutable();
    }

    public static Action breakBlock(BlockPos pos, BlockState state) {
        return new Action(Kind.BREAK, pos, state, null, null);
    }

    /** @param item 要放的物品;规划期未定时传 null */
    public static Action place(BlockPos pos, BlockState current, Item item) {
        return new Action(Kind.PLACE, pos, current, null, item);
    }

    public static Action attack(Entity target) {
        return new Action(Kind.ATTACK, null, null, target, null);
    }

    public static Action useBlock(BlockPos pos, BlockState state) {
        return new Action(Kind.USE_BLOCK, pos, state, null, null);
    }

    public static Action useEntity(Entity target) {
        return new Action(Kind.USE_ENTITY, null, null, target, null);
    }

    public static Action take(BlockPos container, BlockState state, Item item) {
        return new Action(Kind.TAKE, container, state, null, item);
    }

    public static Action drop(Item item) {
        return new Action(Kind.DROP, null, null, null, item);
    }

    /** 回执里点名用:{@code break oak_log at 1,2,3}、{@code attack zombie}。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(kind.verb);
        if (state != null) {
            sb.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(state.getBlock()).getPath());
        } else if (item != null) {
            sb.append(' ').append(net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(item).getPath());
        }
        if (entity != null) {
            sb.append(' ').append(entity.getName().getString());
        }
        if (pos != null) {
            sb.append(" at ").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
        }
        return sb.toString();
    }
}
