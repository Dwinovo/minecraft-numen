package com.dwinovo.numen.core.route;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.core.nav.ThrowawayBlocks;
import com.dwinovo.numen.core.tools.ScanOps;
import com.dwinovo.numen.pathing.spec.BlockBans;
import com.dwinovo.numen.pathing.spec.PositionCosts;
import com.dwinovo.numen.pathing.spec.PositionCosts.Use;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.Semantics;
import com.dwinovo.numen.sdk.BadValue;
import com.dwinovo.numen.sdk.Codec;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.LuaCodecs;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positions;

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
 * 一趟路的描述,读好了的:{@code numen.route.plan} 收的那张表({@link Directions})认过、展开之后。像导航软件那样分成几样——去处与
 * 途经点({@code to}、{@code stops},每一站怎样算到了、路过还是停下)、移动方式({@code mode}:走路或驾船)、偏好旋钮({@code costs}:
 * 挖不挖、放不放、要问主人的格算不算能走,各多贵,跳、游、落差、跑酷、最多改几格)、避开({@code avoid} 与
 * {@code avoid_break}/{@code avoid_place}/{@code avoid_step}:格子种类、一格、一个盒子、一堆格子;方块种类)、放开出厂避开的几种
 * ({@code allow})、垫路料({@code materials})。读法与翻成寻路规格({@link #spec})全仓只在这里。
 *
 * <p>描述不是名词:不存、不起名;她常走的路记在札记里或写成模块,要走时再交一次。权限不是描述的一项:规划时每一格自动问权限层,
 * 拒绝的当墙,要问的照 {@code costs.consent} 算贵并列进计划,执行时走到那一格才问主人。
 *
 * @param stops      途经点,最后一个是终点(总要停下)
 * @param avoidKinds {@code avoid} 里点名的格子种类
 * @param bans       {@code avoid_*} 里点名的方块种类
 * @param written    她写的那张表,原样带回计划里({@code spec})
 */
public record Description(List<Stop> stops, Mode mode, Costs costs, Places avoid, Set<Semantics.Kind> avoidKinds,
                          Set<Semantics.Kind> allowed, Places avoidBreak, Places avoidPlace, Places avoidStep,
                          BlockBans bans, List<Item> materials, Directions written) {

    /** 移动方式。 */
    public enum Mode {
        /** 走路:寻路模块规划与执行。 */
        WALK,
        /** 驾船:坐在船上时,在水面上开到离每一站最近的水格。 */
        BOAT
    }

    /**
     * 能放开的:出厂规格避开、而放开只是这一趟愿不愿意的那几种——流水(会把她推离路线)、机关(压力板、绊线)、易碎(耕地、
     * 海龟蛋)。岩浆与危险方块碰了就伤身,不在其中。
     */
    public enum Allowable {
        FLOWING_WATER(Semantics.Kind.FLOWING_WATER), TRIGGER(Semantics.Kind.TRIGGER), FRAGILE(Semantics.Kind.FRAGILE);

        final Semantics.Kind kind;

        Allowable(Semantics.Kind kind) {
            this.kind = kind;
        }
    }

    /** 能避开的格子种类:门、攀爬、水……每一种都可以。 */
    private static final Set<Semantics.Kind> AVOIDABLE = EnumSet.allOf(Semantics.Kind.class);

    /**
     * {@code numen.route.plan} 收的那张表,也是计划带回来的 {@code spec}:她写了什么就是什么,改一项再交一次就是新的一趟。
     */
    @Doc("Where to go and how, as numen.route.plan takes it; a Plan carries it back as spec.")
    public record Directions(
            @Doc("The destination: a Pos, a Block or an Entity (anything with a pos goes as its cell; an entity as where "
                    + "it is when planned), a Cluster from a scan or Cells (any of its cells), a column {x = …, z = …}, "
                    + "or a height {y = …}.") @Omitted("end at the last of stops") Optional<Target> to,
            @Doc("What counts as there. at: stand in that cell (column, height; any cell of a Cluster). near: within "
                    + "range of it. use: stand where that block is in sight and in reach, to use it. dig: stand where "
                    + "your hand reaches it (for a Cluster, the most of its cells), even if something is in the way, to "
                    + "dig it with numen.work.dig. place: stand where your hand reaches that cell, air too, without "
                    + "standing in it, to build into it with numen.build.place. away: at least range blocks from it (to "
                    + "get away from something).") @Omitted("arrive at") Optional<Stop.Arrive> arrive,
            @Doc("With arrive near: how close counts as there; with arrive away: how far to get (1-" + Stop.MAX_RANGE
                    + ").") @Omitted("near " + Stop.DEFAULT_NEAR + ", away " + Stop.DEFAULT_AWAY) Optional<Integer> range,
            @Doc("Waypoints before the destination, in order: {{to = …, type = \"through\"}, …}. A through stop is "
                    + "passed without stopping; a stop one is come to rest at first.")
            @Omitted("go straight to the destination") Optional<List<Stop>> stops,
            @Doc("How to travel. walk: on foot. boat: steer the boat you sit in across the water, to the water nearest "
                    + "each stop (a stop on land ends at the shore).") @Omitted("walk") Optional<Mode> mode,
            @Doc("Preference knobs: {dig = …, place = …, consent = …, jump = …, swim = …, fall = …, parkour = …, "
                    + "max_changes = …}; see the Costs class.")
            @Omitted("change no block; cells needing your owner's consent count 10 times as dear when changing is "
                    + "allowed") Optional<Costs> costs,
            @Doc("Keep out of these entirely: cell types by name (\"water\", \"door\", \"climbable\" …), a cell (a Pos "
                    + "or a Block), a box {Pos, Pos} written as one item ({{x = 0, y = 60, z = 0}, {x = 9, y = 70, "
                    + "z = 9}}), a Cluster or Cells. Never stood in or on.")
            @Omitted("keep out of only what is kept out by default (lava, hazards, flowing water, triggers, fragile "
                    + "cells)") Optional<Places> avoid,
            @Doc("Cell types kept out by default that this walk may use: flowing_water (currents push her off the "
                    + "route), trigger (pressure plates and tripwires), fragile (farmland and turtle eggs).")
            @Omitted("keep out of them") Optional<List<Allowable>> allow,
            @Doc("Never break these blocks (ids or #tags), or anything in these cells, boxes, Clusters or Cells.")
            @Omitted("ban no block by name") Optional<Places> avoidBreak,
            @Doc("Never place a block into cells holding these (ids or #tags), or into these cells, boxes, Clusters or "
                    + "Cells.") @Omitted("ban no block by name") Optional<Places> avoidPlace,
            @Doc("Never stand on these blocks (ids or #tags, e.g. #minecraft:crops), or on these cells, boxes, Clusters "
                    + "or Cells.") @Omitted("ban no block by name") Optional<Places> avoidStep,
            @Doc("Blocks this walk may spend to pillar up or bridge, best first (ids or #tags). Anything listed is used "
                    + "up and never comes back.")
            @Omitted("spend the plain blocks of the numen:throwaway tag (dirt, cobblestone, netherrack …)")
            Optional<List<String>> materials) {

        /** 同一张表,{@code avoid} 换成 {@code places}。 */
        public Directions avoiding(Places places) {
            return new Directions(to, arrive, range, stops, mode, costs, Optional.of(places), allow, avoidBreak,
                    avoidPlace, avoidStep, materials);
        }
    }

    /**
     * 写下的那张表认成一份描述。
     *
     * @throws IllegalArgumentException 写法对了、意思不成立:既没有终点也没有途经点、船不认的到达方式、种类或料认不出
     */
    public static Description of(Directions d) {
        List<Stop> stops = new ArrayList<>(d.stops().orElse(List.of()));
        if (d.to().isPresent()) {
            stops.add(Stop.of(d.to().get(), d.arrive().orElse(null), d.range().orElse(null), false));
        } else if (d.arrive().isPresent() || d.range().isPresent()) {
            throw new IllegalArgumentException("arrive and range go with to — give the destination as to");
        } else if (stops.isEmpty()) {
            throw new IllegalArgumentException("give to = the destination (and stops = the waypoints before it, if "
                    + "any)");
        } else {
            Stop last = stops.remove(stops.size() - 1);
            stops.add(new Stop(last.to(), last.arrive(), last.range(), false));
        }
        Mode mode = d.mode().orElse(Mode.WALK);
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
        d.allow().orElse(List.of()).forEach(a -> allowed.add(a.kind));
        Places avoid = d.avoid().orElse(Places.NONE);
        Places avoidBreak = d.avoidBreak().orElse(Places.NONE);
        Places avoidPlace = d.avoidPlace().orElse(Places.NONE);
        Places avoidStep = d.avoidStep().orElse(Places.NONE);
        List<Item> materials = d.materials().map(ThrowawayBlocks::of).orElseGet(ThrowawayBlocks::factory);
        return new Description(List.copyOf(stops), mode, d.costs().orElse(Costs.NONE), avoid, avoid.kinds(), allowed,
                avoidBreak, avoidPlace, avoidStep,
                new BlockBans(avoidBreak.blocks("avoid_break"), avoidPlace.blocks("avoid_place"),
                        avoidStep.blocks("avoid_step")), materials, d);
    }

    /**
     * 这一趟的寻路规格:{@code base} 上叠旋钮、避开与放开。{@code base} 的禁令(她盖好的房子不挖)哪一项都放不开。
     */
    public RouteSpec spec(RouteSpec base) {
        RouteSpec.Builder spec = base.edit();
        costs.apply(spec);
        avoidKinds.forEach(spec::exclude);
        allowed.forEach(spec::allow);
        PositionCosts.Builder cells = PositionCosts.builder();
        avoid.into(cells, Use.PASS);
        avoid.into(cells, Use.STAND);
        avoidBreak.into(cells, Use.DIG);
        avoidPlace.into(cells, Use.PLACE);
        avoidStep.into(cells, Use.STAND);
        return spec.positions(base.positions().plus(cells.build())).bans(base.bans().plus(bans)).build();
    }

    // ==================== 避开 ====================

    /**
     * 要避开的地方与东西,写下的样子:名字(在 {@code avoid} 里是格子种类,在 {@code avoid_*} 里是方块 id 或 {@code #标签})、一格、
     * 一个盒子、一堆格子。名字认成什么由用它的那一项定({@link #kinds}、{@link #blocks})。
     *
     * @param written 她写的那一项或那一串,原样
     */
    public record Places(List<String> names, LongSet cells, List<Box> boxes, Object written) {

        static final Places NONE = new Places(List.of(), new LongOpenHashSet(), List.of(), List.of());

        /** 按位置的那几样写进位置表的 {@code use} 这一栏:逐格的照格,盒子整片交进去,不逐格展开。 */
        void into(PositionCosts.Builder table, Use use) {
            cells.forEach((long c) -> table.forbid(use, c));
            boxes.forEach(b -> table.forbid(use, b));
        }

        /** 同一串,再添几格。 */
        public Places plus(List<BlockPos> more) {
            List<Object> items = new ArrayList<>(isBox(written) || !(written instanceof List<?> list) ? List.of(written)
                    : list);
            LongSet all = new LongOpenHashSet(cells);
            for (BlockPos cell : more) {
                items.add(Positions.value(cell));
                all.add(cell.asLong());
            }
            return new Places(names, all, boxes, items);
        }

        /** 名字是格子种类。 */
        Set<Semantics.Kind> kinds() {
            Set<Semantics.Kind> out = EnumSet.noneOf(Semantics.Kind.class);
            for (String name : names) {
                try {
                    Semantics.Kind kind = Semantics.Kind.valueOf(name.toUpperCase(Locale.ROOT));
                    if (AVOIDABLE.contains(kind)) {
                        out.add(kind);
                        continue;
                    }
                } catch (IllegalArgumentException unknown) {
                    // 说法在下面
                }
                throw new IllegalArgumentException("avoid: '" + name + "' is not a cell type; the types are "
                        + String.join(", ", kindNames()) + " — or give a cell, a box or cells");
            }
            return out;
        }

        /** 名字是方块:{@code #ns:tag} 展开成成员,{@code ns:block} 一种。不存在的报错,不静默跳过。 */
        Set<Block> blocks(String option) {
            Set<Block> out = new LinkedHashSet<>();
            for (String raw : names) {
                TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
                if (tag != null) {
                    int before = out.size();
                    for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                        out.add(holder.value());
                    }
                    if (out.size() == before) {
                        throw new IllegalArgumentException(option + ": tag '" + raw + "' has no blocks");
                    }
                    continue;
                }
                ResourceLocation id = ResourceLocation.tryParse(raw);
                Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
                if (block == null) {
                    throw new IllegalArgumentException(option + ": unknown block '" + raw + "' — use a namespaced id "
                            + "like minecraft:chest, a tag like #minecraft:logs, a cell, a box {Pos, Pos} or cells");
                }
                out.add(block);
            }
            return out;
        }

        /** 恰好两个 Pos 的列表:一个盒子的两个对角。两个 Block(带 {@code name} 与 {@code pos})是两格,不是盒子。 */
        private static boolean isBox(Object value) {
            return value instanceof List<?> list && list.size() == 2 && isPos(list.get(0)) && isPos(list.get(1));
        }

        private static boolean isPos(Object value) {
            return value instanceof Map<?, ?> m && m.containsKey("x") && m.containsKey("y") && m.containsKey("z")
                    && !m.containsKey("name");
        }

        /**
         * 一项或一串:字符串是名字,两个 Pos 的列表是一个盒子,别的是一格或一堆格子(一格、一团、一串格)。写回去照原样。
         */
        public static final Codec<Places> CODEC = new Codec<>() {
            @Override
            public ScriptType type() {
                ScriptType pos = LuaCodecs.POS.type();
                ScriptType one = ScriptType.union(ScriptType.STRING, pos, new ScriptType.Named("Block"),
                        ScriptType.listOf(pos), LuaCodecs.of(ScanOps.Cluster.class, "numen").type(),
                        LuaCodecs.CELLS.type());
                return ScriptType.union(one, ScriptType.listOf(one));
            }

            @Override
            public Places decode(Object value) {
                List<Object> items = new ArrayList<>();
                if (value instanceof List<?> list && !isBox(value)) {
                    items.addAll(list);
                } else {
                    items.add(value);
                }
                List<String> names = new ArrayList<>();
                LongSet cells = new LongOpenHashSet();
                List<Box> boxes = new ArrayList<>();
                for (int i = 0; i < items.size(); i++) {
                    Object item = items.get(i);
                    try {
                        if (item instanceof String name) {
                            names.add(name);
                        } else if (isBox(item)) {
                            List<?> box = (List<?>) item;
                            boxes.add(new Box(Positions.cell(box.get(0)), Positions.cell(box.get(1))));
                        } else {
                            Positions.cells(item).forEach(c -> cells.add(c.asLong()));
                        }
                    } catch (BadValue bad) {
                        throw items.size() == 1 ? bad : bad.in("item " + (i + 1));
                    }
                }
                return new Places(List.copyOf(names), cells, List.copyOf(boxes), value);
            }

            @Override
            public Object encode(Places value) {
                return value.written;
            }
        };
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
     * @param written 她写的那张表,已经认过
     */
    public record Costs(Map<String, Object> written) {

        static final Costs NONE = new Costs(Map.of());

        /** 旋钮表在脚本里的样子。 */
        private static final ScriptType.Class CLASS = new ScriptType.Class("Costs",
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

        private static final List<String> KEYS = List.of("dig", "place", "consent", "jump", "swim", "fall", "parkour",
                "max_changes");
        private static final double MAX_PENALTY = 1000.0;
        private static final int MAX_FALL = 64;
        private static final int MAX_CHANGES = 10_000;

        /** 一张旋钮表;键不认得、值的种类或范围不对就读不成,说是哪一个、能写什么。写回去照原样。 */
        public static final Codec<Costs> CODEC = new Codec<>() {
            @Override
            public ScriptType type() {
                return new ScriptType.Named(CLASS.name());
            }

            @Override
            public List<ScriptType.Class> classes() {
                return List.of(CLASS);
            }

            @Override
            public Costs decode(Object value) {
                if (!(value instanceof Map<?, ?> o)) {
                    throw new BadValue("costs is a table: {dig = true, place = true, consent = false …}; got "
                            + BadValue.given(value));
                }
                Map<String, Object> written = new LinkedHashMap<>();
                o.forEach((k, v) -> written.put(String.valueOf(k), v));
                for (String key : written.keySet()) {
                    if (!KEYS.contains(key)) {
                        throw new BadValue("costs takes " + String.join(", ", KEYS) + "; '" + key
                                + "' is not one of them");
                    }
                }
                Costs costs = new Costs(Map.copyOf(written));
                try {
                    costs.apply(RouteSpec.defaults().edit());
                } catch (IllegalArgumentException wrong) {
                    throw new BadValue(wrong.getMessage());
                }
                return costs;
            }

            @Override
            public Object encode(Costs value) {
                return value.written;
            }
        };

        /** 把写了的几项拧到规格上;值不对就报。 */
        void apply(RouteSpec.Builder spec) {
            if (written.containsKey("dig")) {
                Double penalty = switchOrNumber("dig", 0);
                spec.dig(penalty != null);
                if (penalty != null && !Double.isNaN(penalty)) {
                    spec.breakPenalty(penalty);
                }
            }
            if (written.containsKey("place")) {
                Double penalty = switchOrNumber("place", 0);
                spec.place(penalty != null);
                if (penalty != null && !Double.isNaN(penalty)) {
                    spec.placeCost(penalty);
                }
            }
            if (written.containsKey("consent")) {
                Double multiplier = switchOrNumber("consent", 1);
                spec.consent(multiplier != null);
                if (multiplier != null && !Double.isNaN(multiplier)) {
                    spec.consentMultiplier(multiplier);
                }
            }
            if (written.containsKey("jump")) {
                spec.jumpPenalty(number("jump", 0, MAX_PENALTY));
            }
            if (written.containsKey("swim")) {
                spec.wadePenalty(number("swim", 0, MAX_PENALTY));
            }
            if (written.containsKey("fall")) {
                spec.maxFallHeightNoWater((int) whole("fall", MAX_FALL));
            }
            if (written.containsKey("parkour")) {
                if (!(written.get("parkour") instanceof Boolean on)) {
                    throw new IllegalArgumentException("costs.parkour is true or false, got "
                            + BadValue.given(written.get("parkour")));
                }
                spec.parkour(on);
            }
            if (written.containsKey("max_changes")) {
                spec.alterBudget((int) whole("max_changes", MAX_CHANGES));
            }
        }

        /**
         * 开关或数:{@code false} 是 null(关),{@code true} 是 NaN(开、用出厂的数),一个数是开、用这个数。
         */
        private Double switchOrNumber(String key, double min) {
            if (written.get(key) instanceof Boolean on) {
                return on ? Double.NaN : null;
            }
            return number(key, min, MAX_PENALTY);
        }

        private double number(String key, double min, double max) {
            if (!(written.get(key) instanceof Number n)) {
                throw new IllegalArgumentException("costs." + key + " is a number" + (key.equals("dig")
                        || key.equals("place") || key.equals("consent") ? ", true or false" : "") + ", got "
                        + BadValue.given(written.get(key)));
            }
            double d = n.doubleValue();
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

    private static List<String> kindNames() {
        return AVOIDABLE.stream().map(k -> k.name().toLowerCase(Locale.ROOT)).toList();
    }
}
