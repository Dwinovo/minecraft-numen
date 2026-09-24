package com.dwinovo.numen.permission;

import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Set;
import java.util.TreeSet;

/**
 * 身体要对世界做的一件具体的事及其目标。不带工具名、不带 JSON:权限是动作的属性,
 * 哪个工具走到这里都送同一种东西。
 *
 * @param kind    动词
 * @param pos     方块动作的格子;实体动作、丢弃与指令为 null
 * @param state   方块动作发生时那一格的方块状态(挖:要挖的;放:要被盖掉的;右键:被点的)
 * @param entity  实体动作的对象
 * @param item    放/拿/丢的物品;规划期还不知道会用哪种耗材时为 null
 * @param command 执行的游戏指令;其余动作为 null
 */
public record Action(Kind kind, BlockPos pos, BlockState state, Entity entity, Item item, CommandLine command) {

    /** 动词。{@link #verb} 是规则文本里写的那个词。 */
    public enum Kind {
        BREAK("break"), PLACE("place"), ATTACK("attack"), USE_BLOCK("use_block"),
        USE_ENTITY("use_entity"), TAKE("take"), DROP("drop"), COMMAND("command");

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

    /**
     * 一条游戏指令。规则按根名认它({@code command(setblock)}),认的是 {@link #names}:她打的那个根,连同服务器
     * 指令树上与它同指一个节点的别名——{@code tp} 与 {@code teleport}、{@code msg} 与 {@code tell}、{@code w}。
     * 主人写 {@code deny command(tp)} 说的是"传送",换个别名不该绕过去。
     *
     * @param line  整行,不带前导 {@code /}
     * @param root  她打的那个根
     * @param names 规则认的名字:{@code root} 与它的别名
     */
    public record CommandLine(String line, String root, Set<String> names) {
        public CommandLine {
            names = Set.copyOf(names);
        }
    }

    public Action {
        pos = pos == null ? null : pos.immutable();
    }

    public static Action breakBlock(BlockPos pos, BlockState state) {
        return new Action(Kind.BREAK, pos, state, null, null, null);
    }

    /** @param item 要放的物品;规划期未定时传 null */
    public static Action place(BlockPos pos, BlockState current, Item item) {
        return new Action(Kind.PLACE, pos, current, null, item, null);
    }

    public static Action attack(Entity target) {
        return new Action(Kind.ATTACK, null, null, target, null, null);
    }

    public static Action useBlock(BlockPos pos, BlockState state) {
        return new Action(Kind.USE_BLOCK, pos, state, null, null, null);
    }

    public static Action useEntity(Entity target) {
        return new Action(Kind.USE_ENTITY, null, null, target, null, null);
    }

    public static Action take(BlockPos container, BlockState state, Item item) {
        return new Action(Kind.TAKE, container, state, null, item, null);
    }

    public static Action drop(Item item) {
        return new Action(Kind.DROP, null, null, null, item, null);
    }

    /**
     * 以她的身份执行一条游戏指令。根名在服务器的指令树上认:与她打的那个根同指一个节点的根(重定向到它的,
     * 或它重定向去的)都算它的别名,见 {@link CommandLine}。
     *
     * @param line 整行,不带前导 {@code /}
     * @param tree 服务器指令树的根——她的指令就在这棵树上解析
     */
    public static Action command(String line, RootCommandNode<?> tree) {
        String text = line.strip();
        int space = text.indexOf(' ');
        String root = space < 0 ? text : text.substring(0, space);
        Set<String> names = new TreeSet<>();
        names.add(root);
        CommandNode<?> typed = tree.getChild(root);
        if (typed != null) {
            CommandNode<?> target = target(typed);
            for (CommandNode<?> node : tree.getChildren()) {
                if (target(node) == target) {
                    names.add(node.getName());
                }
            }
        }
        return new Action(Kind.COMMAND, null, null, null, null, new CommandLine(text, root, names));
    }

    /** 一个根真正指向的节点:别名重定向去的那个,不是别名就是它自己。 */
    private static CommandNode<?> target(CommandNode<?> node) {
        return node.getRedirect() != null ? node.getRedirect() : node;
    }

    /** 回执里点名用:{@code break oak_log at 1,2,3}、{@code attack zombie}、{@code command /give @s diamond}。 */
    public String describe() {
        StringBuilder sb = new StringBuilder(kind.verb);
        if (command != null) {
            sb.append(" /").append(command.line());
        } else if (state != null) {
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
