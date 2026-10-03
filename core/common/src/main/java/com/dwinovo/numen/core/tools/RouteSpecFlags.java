package com.dwinovo.numen.core.tools;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.BlockCellOrArea;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.nav.NamedAreas;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/**
 * 路线规格的命令面:{@code move.goto_}、{@code route.new} 与 {@code route.spec} 共用的那一串标志({@link #PARAMS})
 * 长什么样,读好的值怎么变成 {@link RouteSpec}({@link #parse})——标志到规格的翻译全仓只此一处。旋钮名用模型看得懂的
 * 普通词,按规格的四组组织:
 * <ul>
 *   <li>能力:{@code alter}(none/natural/any)、{@code parkour}、{@code max_fall}、{@code alter_budget};</li>
 *   <li>格子种类与禁区:{@code --avoid}——还要排除的语义种类({@link Semantics.Kind} 名),或不进入的区域 {@code area:名字};
 *       {@code --allow}——放开出厂排除的那几种里可以放开的;</li>
 *   <li>按位置 / 按种类:{@code --avoid-break}、{@code --avoid-place}、{@code --avoid-step}——方块 id、{@code #标签},
 *       一格(脚本里是一个 Pos,存下的命令行写法是 {@code x,y,z}),或一块区域 {@code area:名字}、{@code area:名字/部分}({@link BlockCellOrArea})。一片地方只有区域一种
 *       写法。</li>
 *   <li>动作代价:{@code --penalty-place}、{@code --penalty-break}、{@code --penalty-jump}、{@code --penalty-wade}。</li>
 * </ul>
 * 全部可选,不给的保持调用方的默认规格:goto 与路线是出厂值(只走不改),mine 是它自己的默认(可以改地形,要主人同意的
 * 格也算进去)。路线把写下的标志原样存成文字,用时再经这里翻译({@code core.route.RouteFlags})。写法上的错(不是数、坐标缺一截、不在几个固定值里)由参数类型在解析时报;这里报的是写法对了、意思不成立的
 * ——方块 id 不存在、标签是空的、超出范围、点名的区域不在——每一条都说清能写什么,模型下一次就写对。
 *
 * <p>区域按名字在翻译的那一刻解析({@link NamedAreas}):路线存的是名字,每次规划按当时的区域;交给寻路的是整块区域的判定
 * ({@link NamedAreas#region}),不逐格展开。
 */
public final class RouteSpecFlags {

    private RouteSpecFlags() {}

    private static final double MAX_PENALTY = 1000.0;
    private static final int MAX_FALL = 64;
    private static final int MAX_ALTER_BUDGET = 10_000;
    /** 这一串标志在用法行里写成的那一格 {@code [route flags]};完整清单在动作自己的帮助里。 */
    public static final String GROUP = "route flags";

    /** 能排除的种类:门、攀爬、水……每一种都可以。 */
    private static final Set<Semantics.Kind> AVOIDABLE = EnumSet.allOf(Semantics.Kind.class);

    /**
     * 能放开的:出厂规格排除、而放开只是这一趟愿不愿意的那几种——流水(会把她推离路线)、机关(压力板、绊线)、易碎(耕地、
     * 海龟蛋)。岩浆与危险方块碰了就伤身,不在其中。
     */
    private static final Set<Semantics.Kind> ALLOWABLE = EnumSet.of(Semantics.Kind.FLOWING_WATER,
            Semantics.Kind.TRIGGER, Semantics.Kind.FRAGILE);

    static final Param<String> ALTER = Param.optional("alter", ArgType.oneOf("none", "natural", "any"),
            "Whether the walk may change the world: none never breaks or places a block; natural may dig, bridge "
                    + "and pillar through natural terrain; any also counts blocks that need your owner's consent, "
                    + "asking before it touches them. Every change is itemised in the result.")
            .whenOmitted("keep this walk's default: change no block")
            .group(GROUP);
    static final Param<List<String>> AVOID = Param.optional("avoid",
            ArgType.list(ArgType.oneOfOrArea(kindNames(AVOIDABLE))),
            "Cell types to keep out of entirely, e.g. water to stay dry, door to never pass doors; or an area of your "
                    + "owner's to stay out of, e.g. area:farm (never stand in it or on it).")
            .whenOmitted("keep out of only what is kept out by default (lava, hazards, fragile cells…)")
            .group(GROUP);
    static final Param<List<String>> ALLOW = Param.optional("allow", ArgType.list(ArgType.oneOf(kindNames(ALLOWABLE))),
            "Cell types kept out by default that this walk may use: flowing_water (currents push her off the route),"
                    + " trigger (pressure plates and tripwires), fragile (farmland and turtle eggs).")
            .whenOmitted("keep out of them")
            .group(GROUP);
    static final Param<Double> PENALTY_PLACE = penalty("place", "Extra cost per block placed", 20);
    static final Param<Double> PENALTY_BREAK = penalty("break", "Extra cost per block broken, on top of dig time", 30);
    static final Param<Double> PENALTY_JUMP = penalty("jump", "Extra cost per jump; raise it for a flatter walk", 2);
    static final Param<Double> PENALTY_WADE = penalty("wade", "Extra cost per block of water walked", 3);
    static final Param<List<BlockCellOrArea>> AVOID_BREAK = Param.optional("avoid_break",
            ArgType.list(ArgType.blockCellOrArea()),
            "Never break these blocks, or anything in these cells or areas (e.g. area:house).")
            .whenOmitted("ban no block by name")
            .group(GROUP);
    static final Param<List<BlockCellOrArea>> AVOID_PLACE = Param.optional("avoid_place",
            ArgType.list(ArgType.blockCellOrArea()),
            "Never place a block into cells holding these (e.g. minecraft:water), or into these cells or areas.")
            .whenOmitted("ban no block by name")
            .group(GROUP);
    static final Param<List<BlockCellOrArea>> AVOID_STEP = Param.optional("avoid_step",
            ArgType.list(ArgType.blockCellOrArea()),
            "Never stand on these blocks (e.g. minecraft:farmland, #minecraft:crops), or on these cells or areas.")
            .whenOmitted("ban no block by name")
            .group(GROUP);
    static final Param<Boolean> PARKOUR = Param.optional("parkour", ArgType.bool(),
            "Allow running jumps over 2-4 block gaps.").whenOmitted("not jump gaps")
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
    public static final List<Param<?>> PARAMS = List.of(ALTER, AVOID, ALLOW, PENALTY_PLACE, PENALTY_BREAK, PENALTY_JUMP,
            PENALTY_WADE, AVOID_BREAK, AVOID_PLACE, AVOID_STEP, PARKOUR, MAX_FALL_FLAG, ALTER_BUDGET);

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
     * @param areas 点名的区域在这里按名字找(她此刻的维度、主人此刻的区域)
     * @throws IllegalArgumentException 写法对了、意思不成立:名字打错、方块不存在、超出范围、区域不在
     */
    public static RouteSpec parse(CommandArgs args, RouteSpec base, NamedAreas areas) {
        if (!given(args)) {
            return base;
        }
        RouteSpec.Builder spec = base.edit();
        if (args.get(ALTER) != null) {
            spec.alter(RouteSpec.Alter.valueOf(args.get(ALTER).toUpperCase(Locale.ROOT)));
        }
        PositionCosts.Builder cells = PositionCosts.builder();
        if (args.get(AVOID) != null) {
            for (String name : args.get(AVOID)) {
                AreaRef area = AreaRef.marked(name);
                if (area == null) {
                    spec.exclude(Semantics.Kind.valueOf(name.toUpperCase(Locale.ROOT)));
                } else {
                    // 不进入:身体不占它的格,脚下也不踩它的格
                    PositionCosts.Region region = region(AVOID, area, areas);
                    cells.forbid(Use.PASS, region).forbid(Use.STAND, region);
                }
            }
        }
        if (args.get(ALLOW) != null) {
            for (String name : args.get(ALLOW)) {
                spec.allow(Semantics.Kind.valueOf(name.toUpperCase(Locale.ROOT)));
            }
        }
        if (args.get(PENALTY_PLACE) != null) {
            spec.placeCost(penalty(PENALTY_PLACE, args));
        }
        if (args.get(PENALTY_BREAK) != null) {
            spec.breakPenalty(penalty(PENALTY_BREAK, args));
        }
        if (args.get(PENALTY_JUMP) != null) {
            spec.jumpPenalty(penalty(PENALTY_JUMP, args));
        }
        if (args.get(PENALTY_WADE) != null) {
            spec.wadePenalty(penalty(PENALTY_WADE, args));
        }
        Bans breaking = bans(AVOID_BREAK, args, areas);
        Bans placing = bans(AVOID_PLACE, args, areas);
        Bans standing = bans(AVOID_STEP, args, areas);
        breaking.into(cells, Use.DIG);
        placing.into(cells, Use.PLACE);
        standing.into(cells, Use.STAND);
        spec.positions(base.positions().plus(cells.build()))
                .bans(base.bans().plus(new BlockBans(breaking.blocks, placing.blocks, standing.blocks)));
        if (args.get(PARKOUR) != null) {
            spec.parkour(args.get(PARKOUR));
        }
        if (args.get(MAX_FALL_FLAG) != null) {
            spec.maxFallHeightNoWater(nonNegative(MAX_FALL_FLAG, args));
        }
        if (args.get(ALTER_BUDGET) != null) {
            spec.alterBudget(nonNegative(ALTER_BUDGET, args));
        }
        return spec.build();
    }

    // ==================== 单项翻译 ====================

    private static String[] kindNames(Set<Semantics.Kind> kinds) {
        return kinds.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }

    private static double penalty(Param<Double> flag, CommandArgs args) {
        double v = args.get(flag);
        if (v < 0 || v > MAX_PENALTY) {
            throw new IllegalArgumentException(
                    flagName(flag) + " must be between 0 and " + (int) MAX_PENALTY + ", got " + v);
        }
        return v;
    }

    private static int nonNegative(Param<Integer> flag, CommandArgs args) {
        int v = args.get(flag);
        if (v < 0) {
            throw new IllegalArgumentException(flagName(flag) + " must be 0 or more, got " + v);
        }
        return v;
    }

    /** 一栏禁令:按种类的方块集合、按位置的单格、整块的区域。 */
    private record Bans(Set<Block> blocks, LongSet cells, List<PositionCosts.Region> regions) {

        /** 按位置的那两样写进位置表的 {@code use} 这一栏。 */
        void into(PositionCosts.Builder table, Use use) {
            cells.forEach((long c) -> table.forbid(use, c));
            regions.forEach(r -> table.forbid(use, r));
        }
    }

    private static Bans bans(Param<List<BlockCellOrArea>> flag, CommandArgs args, NamedAreas areas) {
        Set<Block> blocks = new LinkedHashSet<>();
        LongSet cells = new LongOpenHashSet();
        List<PositionCosts.Region> regions = new ArrayList<>();
        List<BlockCellOrArea> given = args.get(flag);
        if (given == null) {
            return new Bans(blocks, cells, regions);
        }
        for (BlockCellOrArea one : given) {
            if (one.area() != null) {
                regions.add(region(flag, one.area(), areas));
            } else if (one.cell() != null) {
                cells.add(one.cell().asLong());
            } else {
                blocks.addAll(blocksOf(flag, one.block()));
            }
        }
        return new Bans(blocks, cells, regions);
    }

    /** 点名的区域交给寻路的样子;不在就报,说法照 {@link NamedAreas#resolve},前面写上是哪个标志。 */
    private static PositionCosts.Region region(Param<?> flag, AreaRef area, NamedAreas areas) {
        try {
            return NamedAreas.region(areas.resolve(area));
        } catch (IllegalArgumentException missing) {
            throw new IllegalArgumentException(flagName(flag) + " " + area.marked() + ": " + missing.getMessage(),
                    missing);
        }
    }

    /** {@code #ns:tag} 展开成成员;{@code ns:block} 一种。不存在的报错,不静默跳过。 */
    private static Set<Block> blocksOf(Param<?> flag, String raw) {
        Set<Block> out = new LinkedHashSet<>();
        TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
        if (tag != null) {
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            if (out.isEmpty()) {
                throw new IllegalArgumentException(flagName(flag) + ": tag '" + raw + "' has no blocks");
            }
            return out;
        }
        Block block = BuiltInRegistries.BLOCK.getOptional(ResourceLocation.parse(raw)).orElse(null);
        if (block == null) {
            throw new IllegalArgumentException(flagName(flag) + ": unknown block '" + raw
                    + "' — use a namespaced id like minecraft:chest, a tag like #minecraft:logs,"
                    + " a cell like {x = 12, y = 60, z = 8}, or an area like area:house");
        }
        out.add(block);
        return out;
    }

    /** 回执里写标志的样子:命令行上的短横线写法。 */
    /** 说法里点名一个标志:脚本里选项表的那个键。 */
    private static String flagName(Param<?> flag) {
        return flag.name();
    }
}
