package com.dwinovo.numen.core.tools;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.pathing.spec.CellClass;
import com.dwinovo.numen.core.pathing.spec.PositionCosts;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 路线规格的命令面:{@code move goto}、{@code move route} 与 {@code work mine} 共用的那一串标志({@link #PARAMS})
 * 长什么样,读好的值怎么变成 {@link RouteSpec}({@link #parse})——标志到规格的翻译全仓只此一处。旋钮名用模型看得懂的
 * 普通词,按规格的四组组织:
 * <ul>
 *   <li>能力:{@code --alter}(none/natural)、{@code --parkour}、{@code --climb_vines}、{@code --max_fall}、
 *       {@code --alter_budget};</li>
 *   <li>每类代价:{@code --avoid}——要排除的格子类型({@link CellClass} 名);</li>
 *   <li>按位置 / 按种类:{@code --avoid_break}、{@code --avoid_place}、{@code --avoid_step}——方块 id、{@code #标签},
 *       或坐标 {@code x,y,z} / 坐标盒 {@code x1,y1,z1..x2,y2,z2};</li>
 *   <li>动作代价:{@code --penalty_place}、{@code --penalty_break}、{@code --penalty_jump}、{@code --penalty_wade}。</li>
 * </ul>
 * 全部可选,不给的保持调用方的默认规格:goto 与 route 是出厂值(只走不改),mine 是它自己的默认(可以改地形,要主人同意的
 * 格也算进去)。写法上的错(不是数、坐标缺一截、不在几个固定值里)由参数类型在解析时报;这里报的是写法对了、意思不成立的
 * ——方块 id 不存在、标签是空的、超出范围——每一条都说清能写什么,模型下一次就写对。
 */
public final class RouteSpecFlags {

    private RouteSpecFlags() {}

    /** 坐标盒两角之间的分隔。scan_blocks 的包围盒也这么写,模型能原样填进 --avoid_break。 */
    public static final String BOX_SEPARATOR = "..";
    private static final double MAX_PENALTY = 1000.0;
    private static final int MAX_FALL = 64;
    private static final int MAX_ALTER_BUDGET = 10_000;
    /** 这一串标志在用法行里写成的那一格 {@code [route flags]};完整清单在动作自己的帮助里。 */
    public static final String GROUP = "route flags";

    static final Param<String> ALTER = Param.optional("alter", ArgType.oneOf("none", "natural", "any"),
            "Whether the walk may change the world: none never breaks or places a block; natural may dig, bridge "
                    + "and pillar through natural terrain; any also counts blocks that need your owner's consent, "
                    + "asking before it touches them. Every change is itemised in the result.")
            .group(GROUP);
    static final Param<List<String>> AVOID = Param.optional("avoid", ArgType.list(ArgType.oneOf(cellNames())),
            "Cell types to keep out of entirely, e.g. water to stay dry, door to never pass doors.")
            .group(GROUP);
    static final Param<Double> PENALTY_PLACE = penalty("place", "Extra cost per block placed", 20);
    static final Param<Double> PENALTY_BREAK = penalty("break", "Extra cost per block broken, on top of dig time", 30);
    static final Param<Double> PENALTY_JUMP = penalty("jump", "Extra cost per jump; raise it for a flatter walk", 2);
    static final Param<Double> PENALTY_WADE = penalty("wade", "Extra cost per block of water walked", 3);
    static final Param<List<String>> AVOID_BREAK = Param.optional("avoid_break", ArgType.list(ArgType.blockOrCells()),
            "Never break these blocks, or anything in these cells.")
            .group(GROUP);
    static final Param<List<String>> AVOID_PLACE = Param.optional("avoid_place", ArgType.list(ArgType.blockOrCells()),
            "Never place a block into cells holding these (e.g. minecraft:water), or into these cells.")
            .group(GROUP);
    static final Param<List<String>> AVOID_STEP = Param.optional("avoid_step", ArgType.list(ArgType.blockOrCells()),
            "Never stand on these blocks (e.g. minecraft:farmland, #minecraft:crops), or on these cells.")
            .group(GROUP);
    static final Param<Boolean> PARKOUR = Param.optional("parkour", ArgType.bool(),
            "Allow running jumps over 2-4 block gaps.").whenOmitted("not jump gaps")
            .group(GROUP);
    static final Param<Boolean> CLIMB_VINES = Param.optional("climb_vines", ArgType.bool(), "Allow climbing vines.")
            .whenOmitted("not climb vines")
            .group(GROUP);
    static final Param<Integer> MAX_FALL_FLAG = Param.optional("max_fall", ArgType.integer(0, MAX_FALL),
            "Highest drop she may take without water below, in blocks; she may still fall further when her health "
                    + "can take it.").whenOmitted("keep 3")
            .group(GROUP);
    static final Param<Integer> ALTER_BUDGET = Param.optional("alter_budget", ArgType.integer(0, MAX_ALTER_BUDGET),
            "How many blocks the whole route may change (broken + placed). Routes over it are dropped; if none fits, "
                    + "the reply says so. Checked when the route is planned; a re-plan after being blocked still "
                    + "itemises every block actually changed.")
            .whenOmitted("put no limit on it")
            .group(GROUP);

    /** 这一串标志,按帮助里列的顺序。用它的动作把它接在自己的参数之后。 */
    public static final List<Param<?>> PARAMS = List.of(ALTER, AVOID, PENALTY_PLACE, PENALTY_BREAK, PENALTY_JUMP,
            PENALTY_WADE, AVOID_BREAK, AVOID_PLACE, AVOID_STEP, PARKOUR, CLIMB_VINES, MAX_FALL_FLAG, ALTER_BUDGET);

    private static Param<Double> penalty(String action, String what, int byDefault) {
        return Param.optional("penalty_" + action, ArgType.number(0, MAX_PENALTY),
                what + "; higher makes the planner prefer a longer route over doing it.")
                .whenOmitted("keep " + byDefault)
                .group(GROUP);
    }

    /** 这一行写了哪怕一个规格标志。 */
    public static boolean given(CommandArgs args) {
        return PARAMS.stream().anyMatch(p -> args.get(p) != null);
    }

    /**
     * 把写了的标志叠在调用方自己的默认规格 {@code base} 上:没写的保持 base 的值,按位置与按种类的禁令并进 base 已有的那些。
     * 一个都没写就是 base 本身。
     *
     * @throws IllegalArgumentException 写法对了、意思不成立:名字打错、方块不存在、超出范围
     */
    public static RouteSpec parse(CommandArgs args, RouteSpec base) {
        if (!given(args)) {
            return base;
        }
        RouteSpec spec = base;
        if (args.get(ALTER) != null) {
            spec = spec.withAlter(RouteSpec.Alter.valueOf(args.get(ALTER).toUpperCase(Locale.ROOT)));
        }
        if (args.get(AVOID) != null) {
            for (String name : args.get(AVOID)) {
                spec = spec.withCellCost(CellClass.valueOf(name.toUpperCase(Locale.ROOT)), RouteSpec.FORBID);
            }
        }
        if (args.get(PENALTY_PLACE) != null) {
            spec = spec.withPlaceCost(penalty(PENALTY_PLACE, args));
        }
        if (args.get(PENALTY_BREAK) != null) {
            spec = spec.withBreakPenalty(penalty(PENALTY_BREAK, args));
        }
        if (args.get(PENALTY_JUMP) != null) {
            spec = spec.withJumpPenalty(penalty(PENALTY_JUMP, args));
        }
        if (args.get(PENALTY_WADE) != null) {
            spec = spec.withWadePenalty(penalty(PENALTY_WADE, args));
        }
        Bans breaking = bans(AVOID_BREAK, args);
        Bans placing = bans(AVOID_PLACE, args);
        Bans standing = bans(AVOID_STEP, args);
        PositionCosts.Builder cells = PositionCosts.builder();
        breaking.cells.forEach((long c) -> cells.dig(c, RouteSpec.FORBID));
        placing.cells.forEach((long c) -> cells.place(c, RouteSpec.FORBID));
        standing.cells.forEach((long c) -> cells.stand(c, RouteSpec.FORBID));
        RouteSpec.BlockBans held = spec.bans();
        spec = spec.withPositions(spec.positions().plus(cells.build()))
                .withBans(new RouteSpec.BlockBans(union(held.breaking(), breaking.blocks),
                        union(held.placingInto(), placing.blocks), union(held.standingOn(), standing.blocks)));
        if (args.get(PARKOUR) != null) {
            spec = spec.withParkour(args.get(PARKOUR));
        }
        if (args.get(CLIMB_VINES) != null) {
            spec = spec.withClimbVines(args.get(CLIMB_VINES));
        }
        if (args.get(MAX_FALL_FLAG) != null) {
            spec = spec.withMaxFallHeightNoWater(nonNegative(MAX_FALL_FLAG, args));
        }
        if (args.get(ALTER_BUDGET) != null) {
            spec = spec.withAlterBudget(nonNegative(ALTER_BUDGET, args));
        }
        return spec;
    }

    // ==================== 单项翻译 ====================

    /** 可排除的类型名,由 {@link CellClass#avoidable()} 定,不另抄一份。 */
    private static String[] cellNames() {
        return java.util.Arrays.stream(CellClass.values())
                .filter(CellClass::avoidable)
                .map(c -> c.name().toLowerCase(Locale.ROOT))
                .toArray(String[]::new);
    }

    private static double penalty(Param<Double> flag, CommandArgs args) {
        double v = args.get(flag);
        if (v < 0 || v > MAX_PENALTY) {
            throw new IllegalArgumentException(
                    "--" + flag.name() + " must be between 0 and " + (int) MAX_PENALTY + ", got " + v);
        }
        return v;
    }

    private static int nonNegative(Param<Integer> flag, CommandArgs args) {
        int v = args.get(flag);
        if (v < 0) {
            throw new IllegalArgumentException("--" + flag.name() + " must be 0 or more, got " + v);
        }
        return v;
    }

    private static Set<Block> union(Set<Block> held, Set<Block> added) {
        Set<Block> out = new LinkedHashSet<>(held);
        out.addAll(added);
        return out;
    }

    /** 一栏禁令:按种类的方块集合 + 按位置的格子集合。 */
    private record Bans(Set<Block> blocks, LongSet cells) {}

    private static Bans bans(Param<List<String>> flag, CommandArgs args) {
        Set<Block> blocks = new LinkedHashSet<>();
        LongSet cells = new LongOpenHashSet();
        List<String> given = args.get(flag);
        if (given == null) {
            return new Bans(blocks, cells);
        }
        for (String raw : given) {
            if (isCells(raw)) {
                cells.addAll(cellsOf(raw));
            } else {
                blocks.addAll(blocksOf(flag, raw));
            }
        }
        return new Bans(blocks, cells);
    }

    /** 以数字或负号开头的就是坐标——方块 id 从字母或 # 开头({@link ArgType#blockOrCells} 同一条分法)。 */
    private static boolean isCells(String raw) {
        return Character.isDigit(raw.charAt(0)) || raw.charAt(0) == '-';
    }

    /** {@code x,y,z} 一格,或 {@code x1,y1,z1..x2,y2,z2} 一个盒子(两角任意顺序)。写法已由参数类型读过。 */
    private static LongSet cellsOf(String raw) {
        LongSet out = new LongOpenHashSet();
        int sep = raw.indexOf(BOX_SEPARATOR);
        BlockPos a = corner(sep < 0 ? raw : raw.substring(0, sep));
        BlockPos b = sep < 0 ? a : corner(raw.substring(sep + BOX_SEPARATOR.length()));
        for (BlockPos p : BlockPos.betweenClosed(a, b)) {
            out.add(p.asLong());
        }
        return out;
    }

    private static BlockPos corner(String raw) {
        String[] parts = raw.split(",");
        return new BlockPos(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** {@code #ns:tag} 展开成成员;{@code ns:block} 一种。不存在的报错,不静默跳过。 */
    private static Set<Block> blocksOf(Param<List<String>> flag, String raw) {
        Set<Block> out = new LinkedHashSet<>();
        TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
        if (tag != null) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException("--" + flag.name() + ": tag '" + raw + "' has no blocks");
            }
            return out;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(raw)).orElse(null);
        if (block == null) {
            throw new IllegalArgumentException("--" + flag.name() + ": unknown block '" + raw
                    + "' — use a namespaced id like minecraft:chest, a tag like #minecraft:logs,"
                    + " or a cell/box like 12,60,8 or 12,60,8..15,63,10");
        }
        out.add(block);
        return out;
    }
}
