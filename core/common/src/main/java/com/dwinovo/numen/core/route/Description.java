package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.Shapes;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.nav.ThrowawayBlocks;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/**
 * 一趟路的描述:{@code numen.route.plan} 收的那张表。像导航软件那样分成几样——去处与途经点({@code to}、{@code stops},每一站怎样算到了、
 * 路过还是停下)、移动方式({@code mode}:走路或驾船)、偏好旋钮({@code costs}:挖不挖、放不放、要问主人的格算不算能走,各多贵,
 * 跳、游、落差、跑酷、最多改几格)、避开({@code avoid} 与 {@code avoid_break}/{@code avoid_place}/{@code avoid_step}:格子种类、
 * 一格、一个盒子、一堆格子;方块种类)、放开出厂避开的几种({@code allow})、垫路料({@code materials})。参数表({@link #PARAMS})、
 * 读法与翻成寻路规格({@link #spec})全仓只在这里。
 *
 * <p>描述不是名词:不存、不起名;她常走的路记在札记里或写成模块,要走时再交一次。权限不是描述的一项:规划时每一格自动问权限层,
 * 拒绝的当墙,要问的照 {@code costs.consent} 算贵并列进计划,执行时走到那一格才问主人。
 *
 * @param stops     途经点,最后一个是终点(总要停下)
 * @param written   她写的那张表,原样带回计划里({@code spec})
 */
public record Description(List<Stop> stops, Mode mode, Costs costs, Places avoid, Set<Semantics.Kind> allowed,
                          Places avoidBreak, Places avoidPlace, Places avoidStep, List<Item> materials,
                          Map<String, Object> written) {

    /** 移动方式。 */
    public enum Mode {
        /** 走路:寻路模块规划与执行。 */
        WALK,
        /** 驾船:坐在船上时,在水面上开到离每一站最近的水格。 */
        BOAT
    }

    /** 能避开的格子种类:门、攀爬、水……每一种都可以。 */
    private static final Set<Semantics.Kind> AVOIDABLE = EnumSet.allOf(Semantics.Kind.class);
    /**
     * 能放开的:出厂规格避开、而放开只是这一趟愿不愿意的那几种——流水(会把她推离路线)、机关(压力板、绊线)、易碎(耕地、
     * 海龟蛋)。岩浆与危险方块碰了就伤身,不在其中。
     */
    private static final Set<Semantics.Kind> ALLOWABLE = EnumSet.of(Semantics.Kind.FLOWING_WATER,
            Semantics.Kind.TRIGGER, Semantics.Kind.FRAGILE);

    /** 一处要避开的地方在脚本里的样子。 */
    private static final ScriptType PLACE_SCRIPT = ScriptType.union(Shapes.POS.type(), Shapes.BLOCK.type(),
            ScriptType.listOf(Shapes.POS.type()), ScanOps.CLUSTER.type(), Shapes.CELLS.type());

    public static final Param<Target> TO = Param.optional("to", ArgType.table("place", Target.SCRIPT, Target::read,
                    Target::json),
            "The destination: a Pos, a Block or an Entity (anything with a pos goes as its cell; an entity as where it "
                    + "is when planned), a Cluster from a scan or Cells (any of its cells), a column {x = …, z = …}, or "
                    + "a height {y = …}.")
            .whenOmitted("end at the last of stops");
    public static final Param<String> ARRIVE = Param.optional("arrive", ArgType.oneOf(Stop.ARRIVE_WORDS),
            "What counts as there. at: stand in that cell (column, height; any cell of a Cluster). near: within range "
                    + "of it. use: stand where that block is in sight and in reach, to use it. dig: stand where your "
                    + "hand reaches it (for a Cluster, the most of its cells), even if something is in the way, to dig "
                    + "it with numen.work.dig. place: stand where your hand reaches that cell, air too, without standing in "
                    + "it, to build into it with numen.build.place. away: at least range blocks from it (to get away from "
                    + "something).")
            .whenOmitted("arrive at");
    public static final Param<Integer> RANGE = Param.optional("range", ArgType.integer(1, Stop.MAX_RANGE),
            "With arrive near: how close counts as there; with arrive away: how far to get.")
            .whenOmitted("near " + Stop.DEFAULT_NEAR + ", away " + Stop.DEFAULT_AWAY);
    public static final Param<List<Stop>> STOPS = Param.optional("stops", ArgType.table("stops",
                    ScriptType.listOf(Stop.CLASS.type()), Description::readStops, Description::stopsJson),
            "Waypoints before the destination, in order: {{to = …, type = \"through\"}, …}. A through stop is passed "
                    + "without stopping; a stop one is come to rest at first.")
            .whenOmitted("go straight to the destination");
    public static final Param<String> MODE = Param.optional("mode", ArgType.oneOf("walk", "boat"),
            "How to travel. walk: on foot. boat: steer the boat you sit in across the water, to the water nearest "
                    + "each stop (a stop on land ends at the shore).")
            .whenOmitted("walk");
    public static final Param<Places> AVOID = Param.optional("avoid", ArgType.table("cells or kinds", PLACE_SCRIPT,
                    v -> Places.read(v, true), Places::raw),
            "Keep out of these entirely: cell types by name (\"water\", \"door\", \"climbable\" …), a cell (a Pos or a "
                    + "Block), a box {Pos, Pos} written as one item ({{x = 0, y = 60, z = 0}, {x = 9, y = 70, "
                    + "z = 9}}), a Cluster or Cells. Never stood in or on.")
            .whenOmitted("keep out of only what is kept out by default (lava, hazards, flowing water, triggers, "
                    + "fragile cells)");
    public static final Param<List<String>> ALLOW = Param.optional("allow", ArgType.list(ArgType.oneOf(
                    kindNames(ALLOWABLE))),
            "Cell types kept out by default that this walk may use: flowing_water (currents push her off the "
                    + "route), trigger (pressure plates and tripwires), fragile (farmland and turtle eggs).")
            .whenOmitted("keep out of them");
    public static final Param<Places> AVOID_BREAK = bans("avoid_break",
            "Never break these blocks (ids or #tags), or anything in these cells, boxes, Clusters or Cells.");
    public static final Param<Places> AVOID_PLACE = bans("avoid_place",
            "Never place a block into cells holding these (ids or #tags), or into these cells, boxes, Clusters or Cells.");
    public static final Param<Places> AVOID_STEP = bans("avoid_step",
            "Never stand on these blocks (ids or #tags, e.g. #minecraft:crops), or on these cells, boxes, "
                    + "Clusters or Cells.");
    public static final Param<Costs> COSTS = Param.optional("costs", ArgType.table("costs", Costs.CLASS.type(),
                    Costs::read, Costs::raw),
            "Preference knobs: {dig = …, place = …, consent = …, jump = …, swim = …, fall = …, parkour = …, "
                    + "max_changes = …}; see the Costs class.")
            .whenOmitted("change no block; cells needing your owner's consent count 10 times as dear when changing "
                    + "is allowed");
    public static final Param<List<String>> MATERIALS = Param.optional("materials", ArgType.list(ArgType.idOrTag()),
            "Blocks this walk may spend to pillar up or bridge, best first (ids or #tags). Anything listed is used "
                    + "up and never comes back.")
            .whenOmitted("spend the plain blocks of the numen:throwaway tag (dirt, cobblestone, netherrack …)");

    /** 描述的全部选项,按帮助里列的顺序。 */
    public static final List<Param<?>> PARAMS = List.of(TO, ARRIVE, RANGE, STOPS, MODE, COSTS, AVOID, ALLOW, AVOID_BREAK,
            AVOID_PLACE, AVOID_STEP, MATERIALS);

    private static Param<Places> bans(String name, String doc) {
        return Param.optional(name, ArgType.table("blocks or cells", ScriptType.union(ScriptType.STRING, PLACE_SCRIPT),
                v -> Places.read(v, false), Places::raw), doc).whenOmitted("ban no block by name");
    }

    /**
     * 读好的参数拼成一份描述。
     *
     * @throws IllegalArgumentException 写法对了、意思不成立:既没有终点也没有途经点、船不认的到达方式、料认不出
     */
    public static Description of(CommandArgs args) {
        List<Stop> stops = new ArrayList<>(args.get(STOPS) != null ? args.get(STOPS) : List.of());
        if (args.get(TO) != null) {
            stops.add(Stop.of(args.get(TO), args.get(ARRIVE), args.get(RANGE), false));
        } else if (args.get(ARRIVE) != null || args.get(RANGE) != null) {
            throw new IllegalArgumentException("arrive and range go with to — give the destination as to");
        } else if (stops.isEmpty()) {
            throw new IllegalArgumentException("give to = the destination (and stops = the waypoints before it, if "
                    + "any)");
        } else {
            Stop last = stops.remove(stops.size() - 1);
            stops.add(new Stop(last.to(), last.arrive(), last.range(), false));
        }
        Mode mode = args.get(MODE) == null ? Mode.WALK : Mode.valueOf(args.get(MODE).toUpperCase(Locale.ROOT));
        if (mode == Mode.BOAT) {
            for (Stop stop : stops) {
                boolean onWater = stop.to() instanceof Target.Cell || stop.to() instanceof Target.Column;
                if (!onWater || (stop.arrive() != Stop.Arrive.AT && stop.arrive() != Stop.Arrive.NEAR)) {
                    throw new IllegalArgumentException("mode = \"boat\" sails to the water nearest each stop: each "
                            + "stop is a Pos or a column {x = …, z = …} with arrive at or near; " + stop.words()
                            + " is not");
                }
            }
        }
        Set<Semantics.Kind> allowed = EnumSet.noneOf(Semantics.Kind.class);
        if (args.get(ALLOW) != null) {
            args.get(ALLOW).forEach(k -> allowed.add(Semantics.Kind.valueOf(k.toUpperCase(Locale.ROOT))));
        }
        List<Item> materials = args.get(MATERIALS) != null ? ThrowawayBlocks.of(args.get(MATERIALS))
                : ThrowawayBlocks.factory();
        return new Description(List.copyOf(stops), mode, args.get(COSTS) != null ? args.get(COSTS) : Costs.NONE,
                orNone(args.get(AVOID)), allowed, orNone(args.get(AVOID_BREAK)), orNone(args.get(AVOID_PLACE)),
                orNone(args.get(AVOID_STEP)), materials, args.optionValues(PARAMS));
    }

    private static Places orNone(Places given) {
        return given != null ? given : Places.NONE;
    }

    /**
     * 这一趟的寻路规格:{@code base} 上叠旋钮、避开与放开。{@code base} 的禁令(她盖好的房子不挖)哪一项都放不开。
     */
    public RouteSpec spec(RouteSpec base) {
        RouteSpec.Builder spec = base.edit();
        costs.apply(spec);
        avoid.kinds().forEach(spec::exclude);
        allowed.forEach(spec::allow);
        PositionCosts.Builder cells = PositionCosts.builder();
        avoid.into(cells, Use.PASS);
        avoid.into(cells, Use.STAND);
        avoidBreak.into(cells, Use.DIG);
        avoidPlace.into(cells, Use.PLACE);
        avoidStep.into(cells, Use.STAND);
        return spec.positions(base.positions().plus(cells.build()))
                .bans(base.bans().plus(new BlockBans(avoidBreak.blocks(), avoidPlace.blocks(), avoidStep.blocks())))
                .build();
    }

    // ==================== 途经点 ====================

    private static List<Stop> readStops(JsonElement value) {
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("stops is a list of stops: {{to = …}, {to = …, type = \"stop\"}}");
        }
        List<Stop> out = new ArrayList<>();
        value.getAsJsonArray().forEach(s -> out.add(Stop.read(s)));
        return List.copyOf(out);
    }

    private static JsonElement stopsJson(List<Stop> stops) {
        JsonArray out = new JsonArray();
        stops.forEach(s -> out.add(s.json()));
        return out;
    }

    // ==================== 避开 ====================

    /**
     * 要避开的地方与东西:格子种类(只在 {@code avoid} 里)或方块种类(只在 {@code avoid_*} 里),一格、一个盒子、一堆格子。
     *
     * @param raw 她写的那一项或那一串,原样
     */
    public record Places(Set<Semantics.Kind> kinds, Set<Block> blocks, LongSet cells, List<Box> boxes, JsonElement raw) {

        static final Places NONE = new Places(Set.of(), Set.of(), new LongOpenHashSet(), List.of(), new JsonArray());

        /** 按位置的那几样写进位置表的 {@code use} 这一栏:逐格的照格,盒子整片交进去,不逐格展开。 */
        void into(PositionCosts.Builder table, Use use) {
            cells.forEach((long c) -> table.forbid(use, c));
            boxes.forEach(b -> table.forbid(use, b));
        }

        /**
         * 一项或一串:字符串是种类({@code kinds} 为真时是格子种类,否则是方块 id 或 {@code #标签}),两个 Pos 的列表是一个盒子,
         * 别的是一格或一堆格子(一格、一团、一串格,读法是 {@link ArgType#cellsOf})。
         */
        static Places read(JsonElement value, boolean kinds) {
            List<JsonElement> items = new ArrayList<>();
            if (value != null && value.isJsonArray() && !isBox(value)) {
                value.getAsJsonArray().forEach(items::add);
            } else {
                items.add(value);
            }
            Set<Semantics.Kind> kindSet = EnumSet.noneOf(Semantics.Kind.class);
            Set<Block> blocks = new LinkedHashSet<>();
            LongSet cells = new LongOpenHashSet();
            List<Box> boxes = new ArrayList<>();
            for (JsonElement item : items) {
                if (item != null && item.isJsonPrimitive() && item.getAsJsonPrimitive().isString()) {
                    String name = item.getAsString();
                    if (kinds) {
                        kindSet.add(kind(name));
                    } else {
                        blocks.addAll(blocksOf(name));
                    }
                } else if (isBox(item)) {
                    JsonArray box = item.getAsJsonArray();
                    boxes.add(new Box(ArgType.cellOf(box.get(0)), ArgType.cellOf(box.get(1))));
                } else {
                    ArgType.cellsOf(item).forEach(c -> cells.add(c.asLong()));
                }
            }
            return new Places(kindSet, blocks, cells, List.copyOf(boxes), value);
        }

        /** 恰好两个 Pos 的列表:一个盒子的两个对角。两个 Block(带 {@code name} 与 {@code pos})是两格,不是盒子。 */
        private static boolean isBox(JsonElement value) {
            return value != null && value.isJsonArray() && value.getAsJsonArray().size() == 2
                    && isPos(value.getAsJsonArray().get(0)) && isPos(value.getAsJsonArray().get(1));
        }

        private static boolean isPos(JsonElement value) {
            return value.isJsonObject() && value.getAsJsonObject().has("x") && value.getAsJsonObject().has("y")
                    && value.getAsJsonObject().has("z") && !value.getAsJsonObject().has("name");
        }

        private static Semantics.Kind kind(String name) {
            try {
                Semantics.Kind kind = Semantics.Kind.valueOf(name.toUpperCase(Locale.ROOT));
                if (AVOIDABLE.contains(kind)) {
                    return kind;
                }
            } catch (IllegalArgumentException unknown) {
                // 说法在下面
            }
            throw new IllegalArgumentException("avoid: '" + name + "' is not a cell type; the types are "
                    + String.join(", ", kindNames(AVOIDABLE)) + " — or give a cell, a box or cells");
        }

        /** {@code #ns:tag} 展开成成员;{@code ns:block} 一种。不存在的报错,不静默跳过。 */
        private static Set<Block> blocksOf(String raw) {
            Set<Block> out = new LinkedHashSet<>();
            TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
            if (tag != null) {
                for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                    out.add(holder.value());
                }
                if (out.isEmpty()) {
                    throw new IllegalArgumentException("tag '" + raw + "' has no blocks");
                }
                return out;
            }
            ResourceLocation id = ResourceLocation.tryParse(raw);
            Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
            if (block == null) {
                throw new IllegalArgumentException("unknown block '" + raw + "' — use a namespaced id like "
                        + "minecraft:chest, a tag like #minecraft:logs, a cell, a box {Pos, Pos} or cells");
            }
            out.add(block);
            return out;
        }
    }

    /** 两格围出的盒子(含两头):整片交给寻路,一格在不在里面现算,不逐格展开。 */
    public record Box(BlockPos a, BlockPos b) implements PositionCosts.Region {

        public Box {
            a = a.immutable();
            b = b.immutable();
        }

        @Override
        public boolean contains(long cell) {
            int x = BlockPos.getX(cell);
            int y = BlockPos.getY(cell);
            int z = BlockPos.getZ(cell);
            return x >= Math.min(a.getX(), b.getX()) && x <= Math.max(a.getX(), b.getX())
                    && y >= Math.min(a.getY(), b.getY()) && y <= Math.max(a.getY(), b.getY())
                    && z >= Math.min(a.getZ(), b.getZ()) && z <= Math.max(a.getZ(), b.getZ());
        }
    }

    // ==================== 旋钮 ====================

    /**
     * 偏好旋钮:{@code costs} 那张表。挖、放是能力也是价钱:{@code false} 是这一趟不挖、不放,{@code true} 是按出厂罚分许它,
     * 一个数是许它、每一下另加这么多罚分。{@code consent} 说要问主人的格:一个数(至少 1)是算能走、价钱乘这个倍数,执行走到那一格时
     * 问主人;{@code false} 是当墙、别打扰主人。其余是罚分与上限。
     *
     * @param raw 她写的那张表,已经认过
     */
    public record Costs(JsonObject raw) {

        static final Costs NONE = new Costs(new JsonObject());

        /** 旋钮表在脚本里的样子。 */
        public static final ScriptType.Class CLASS = new ScriptType.Class("Costs",
                "Preference knobs for one walk; leave out what you don't care about.", null, List.of(
                        ScriptType.optional("dig", ScriptType.union(ScriptType.BOOLEAN, ScriptType.NUMBER),
                                "false: never break a block (default). true: may dig through, each block costing "
                                        + "its dig time plus 30. A number: may dig, with that extra cost per block."),
                        ScriptType.optional("place", ScriptType.union(ScriptType.BOOLEAN, ScriptType.NUMBER),
                                "false: never place a block (default). true: may pillar and bridge with materials, 20 "
                                        + "per block. A number: may, with that cost per block."),
                        ScriptType.optional("consent", ScriptType.union(ScriptType.BOOLEAN, ScriptType.NUMBER),
                                "Cells your owner must agree to change (their builds, their chests …). A number "
                                        + "(default 10): plannable at that many times the price; the plan lists "
                                        + "them and the walk stops at each to ask. false: keep away from them, never "
                                        + "bother your owner."),
                        ScriptType.optional("jump", ScriptType.NUMBER, "Extra cost per jump (default 2); raise it "
                                + "for a flatter walk."),
                        ScriptType.optional("swim", ScriptType.NUMBER, "Extra cost per block through water "
                                + "(default 3)."),
                        ScriptType.optional("fall", ScriptType.INTEGER, "Highest drop to take without water below "
                                + "(default 3); higher only when your health can take it."),
                        ScriptType.optional("parkour", ScriptType.BOOLEAN, "Allow running jumps over 2-4 block gaps "
                                + "(default false)."),
                        ScriptType.optional("max_changes", ScriptType.INTEGER, "How many blocks the whole walk may "
                                + "break and place at most; ways over it are dropped (default: no limit).")));

        private static final Set<String> KEYS = Set.of("dig", "place", "consent", "jump", "swim", "fall", "parkour",
                "max_changes");
        private static final double MAX_PENALTY = 1000.0;
        private static final int MAX_FALL = 64;
        private static final int MAX_CHANGES = 10_000;

        /**
         * @throws IllegalArgumentException 不是一张表、键不认得、值的种类或范围不对:说是哪一个、能写什么
         */
        static Costs read(JsonElement value) {
            if (value == null || !value.isJsonObject()) {
                throw new IllegalArgumentException("costs is a table: {dig = true, place = true, consent = false …}");
            }
            JsonObject o = value.getAsJsonObject();
            for (String key : o.keySet()) {
                if (!KEYS.contains(key)) {
                    throw new IllegalArgumentException("costs takes " + String.join(", ", List.of("dig", "place",
                            "consent", "jump", "swim", "fall", "parkour", "max_changes")) + "; '" + key
                            + "' is not one of them");
                }
            }
            Costs costs = new Costs(o.deepCopy());
            costs.apply(RouteSpec.defaults().edit());
            return costs;
        }

        /** 把写了的几项拧到规格上;值不对就报。 */
        void apply(RouteSpec.Builder spec) {
            if (raw.has("dig")) {
                Double penalty = switchOrNumber("dig", 0);
                spec.dig(penalty != null);
                if (penalty != null && !Double.isNaN(penalty)) {
                    spec.breakPenalty(penalty);
                }
            }
            if (raw.has("place")) {
                Double penalty = switchOrNumber("place", 0);
                spec.place(penalty != null);
                if (penalty != null && !Double.isNaN(penalty)) {
                    spec.placeCost(penalty);
                }
            }
            if (raw.has("consent")) {
                Double multiplier = switchOrNumber("consent", 1);
                spec.consent(multiplier != null);
                if (multiplier != null && !Double.isNaN(multiplier)) {
                    spec.consentMultiplier(multiplier);
                }
            }
            if (raw.has("jump")) {
                spec.jumpPenalty(number("jump", 0, MAX_PENALTY));
            }
            if (raw.has("swim")) {
                spec.wadePenalty(number("swim", 0, MAX_PENALTY));
            }
            if (raw.has("fall")) {
                spec.maxFallHeightNoWater((int) whole("fall", MAX_FALL));
            }
            if (raw.has("parkour")) {
                JsonElement v = raw.get("parkour");
                if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                    throw new IllegalArgumentException("costs.parkour is true or false, got " + v);
                }
                spec.parkour(v.getAsBoolean());
            }
            if (raw.has("max_changes")) {
                spec.alterBudget((int) whole("max_changes", MAX_CHANGES));
            }
        }

        /**
         * 开关或数:{@code false} 是 null(关),{@code true} 是 NaN(开、用出厂的数),一个数是开、用这个数。
         */
        private Double switchOrNumber(String key, double min) {
            JsonElement v = raw.get(key);
            if (v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean()) {
                return v.getAsBoolean() ? Double.NaN : null;
            }
            return number(key, min, MAX_PENALTY);
        }

        private double number(String key, double min, double max) {
            JsonElement v = raw.get(key);
            if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) {
                throw new IllegalArgumentException("costs." + key + " is a number" + (key.equals("dig")
                        || key.equals("place") || key.equals("consent") ? ", true or false" : "") + ", got " + v);
            }
            double d = v.getAsDouble();
            if (d < min || d > max) {
                throw new IllegalArgumentException("costs." + key + " must be " + (long) min + " to " + (long) max
                        + ", got " + d);
            }
            return d;
        }

        private long whole(String key, int max) {
            double d = number(key, 0, max);
            if (d != Math.rint(d)) {
                throw new IllegalArgumentException("costs." + key + " is a whole number, got " + d);
            }
            return (long) d;
        }
    }

    private static String[] kindNames(Set<Semantics.Kind> kinds) {
        return kinds.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);
    }
}
