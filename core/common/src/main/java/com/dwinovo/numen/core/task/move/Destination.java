package com.dwinovo.numen.core.task.move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.Place;
import com.dwinovo.numen.core.nav.DigQuote;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 去处:写法({@link Stop}:一处({@link Place},坐标给几个算几个),或几格,加怎样算到了,{@code arrive = "at"|"use"|"near"|"dig"},
 * {@code --near} 只配 {@code near})与按那一刻的世界编好的寻路目标。写法到目标的对应只在这里:
 * <ul>
 *   <li>{@code at}:位置——{@code x y z} 是那一格,{@code x z} 是那一列,只给 {@code y} 是那个高度({@link Goals#at}、
 *       {@link Goals#column}、{@link Goals#level})。站到一块方块上面也是 {@code at}:坐标是它上面脚所在的那一格;</li>
 *   <li>{@code use}:用那一格方块——站在它敞开的面前、看得见、点得到({@link Goals#use});</li>
 *   <li>{@code near}:离那一格(或那一列)不超过 {@code near} 格({@link Goals#within});</li>
 *   <li>{@code dig}:挖那一格方块——站到手够得着它、身体不占着它、挡着视线的都是 {@code numen.work.dig} 清得掉的地方
 *       ({@link Goals#dig},清不清得掉按 {@link #clearing} 问),那一格本身留给 {@code numen.work.dig}。到了就是 {@code numen.work.dig}
 *       站在这儿办得成,两处问的是同一个判据。</li>
 * </ul>
 * 一格就是只有一格的几格:去几格,四种到达对其中任意一格成立——{@code at} 是走进其中任意一格(站得住的),{@code use} 是用其中
 * 任意一个能点、用得上的方块,{@code near} 是离其中任意一格不超过 {@code near} 格,三种用寻路模块现成的"多个取其一"
 * ({@link Goals#anyOf})组合;{@code dig} 是够得着其中任意一个 {@code numen.work.dig} 挖得成的方块,同样划算的站位里优先一次
 * 够得着最多格的,挖起来贵的格(要问主人的)只在便宜的远出它那份价钱时才去({@link Goals#dig(List, BodyStats, Goals.Clearing)},
 * 定价只在寻路模块那一处)。
 *
 * <p><b>几格的目标有界</b>:只在离出发点最近的 {@link #NEAREST} 格里挑成员,{@code at}、{@code near}、{@code dig} 至多
 * {@link #MEMBERS} 个成员,{@code use} 至多 {@link #USE_MEMBERS} 个(每个要按世界列一遍候选站位,至多试 {@link #USE_TRIES} 个能点的
 * 方块)。离出发点最近的那一侧就是她要走进去的那一侧;那一侧走不通,回执说的也是这一侧。
 *
 * <p>路线的每个途经点存的是写法,规划时照当时的世界编成目标。写错了当场提醒({@link GotoReminders}),不替她改写、不去搜索;要不要
 * 提醒一律问模块({@link Terrain}),这里不另判。
 *
 * @param goal   编好的目标;{@code use} 的候选站位按编的那一刻的世界列定
 * @param toward 给人说"朝哪儿"的那一格(回执里的方向与距离):坐标见 {@link Stop#toward},几格是离出发点最近的那个成员
 */
public record Destination(Stop stop, Goal goal, BlockPos toward) {

    /** 几格里,只在离出发点最近的这么多格里挑成员:一个 16³ 小节的格数。 */
    static final int NEAREST = 4096;
    /** {@code at}、{@code near}、{@code dig} 至多几个成员:估价与判到没到逐个问成员,几十个仍是一次比较的量级。 */
    static final int MEMBERS = 64;
    /** {@code use} 至多几个成员。 */
    static final int USE_MEMBERS = 8;
    /** {@code use} 至多为几个能点的方块列候选站位:每列一个都要在它周围一两千个节点上打射线。 */
    static final int USE_TRIES = 16;

    /** 怎样算到了。 */
    public enum Arrive {
        AT, USE, NEAR, DIG, REACH;

        /** 命令行上的写法。 */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Arrive of(String word) {
            return word == null ? AT : valueOf(word.toUpperCase(Locale.ROOT));
        }
    }

    /** 选项 {@code arrive} 能写的几个。 */
    public static final String[] ARRIVE_WORDS = {"at", "use", "near", "dig", "reach"};

    /** {@code arrive = "near"} 不写 {@code near} 时停在几格内:3 格大致是"就在旁边"。 */
    public static final int DEFAULT_NEAR = 3;

    /**
     * 一个去处的写法:一处(坐标给几个算几个)或几格,加怎样算到了。形状不成立(坐标缺一截、坐标与几格都给了、{@code near} 与到达方式
     * 对不上)在建的时候就报,报的话就是受理回执;和世界有关的在 {@link Destination#of} 里报。存盘的样子({@link #CODEC})是各个字段,
     * 不是命令行上的写法。
     *
     * @param x     没给为 null
     * @param y     没给为 null
     * @param z     没给为 null
     * @param cells 到其中任意一格;给了坐标为 null
     * @param near  {@code arrive=near} 时的距离,否则为 null
     */
    public record Stop(Integer x, Integer y, Integer z, List<BlockPos> cells, Arrive arrive, Integer near) {

        public static final Codec<Stop> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("x").forGetter(s -> Optional.ofNullable(s.x())),
                Codec.INT.optionalFieldOf("y").forGetter(s -> Optional.ofNullable(s.y())),
                Codec.INT.optionalFieldOf("z").forGetter(s -> Optional.ofNullable(s.z())),
                BlockPos.CODEC.listOf().optionalFieldOf("cells").forGetter(s -> Optional.ofNullable(s.cells())),
                Codec.STRING.fieldOf("arrive").forGetter(s -> s.arrive().word()),
                Codec.INT.optionalFieldOf("near").forGetter(s -> Optional.ofNullable(s.near()))
        ).apply(i, (x, y, z, cells, arrive, near) -> new Stop(x.orElse(null), y.orElse(null), z.orElse(null),
                cells.orElse(null), Arrive.of(arrive), near.orElse(null))));

        public Stop {
            if (cells != null) {
                if (x != null || y != null || z != null || cells.isEmpty()) {
                    throw new IllegalArgumentException("a destination is coordinates or several cells, not both");
                }
                cells = List.copyOf(cells);
            } else {
                boolean hasXz = x != null && z != null;
                if ((x == null) != (z == null) || (!hasXz && y == null)) {
                    throw new IllegalArgumentException("a destination is x and z (a place), x, y and z (one cell), y"
                            + " alone (a height), or several cells; got " + (x != null ? "x" : "")
                            + (y != null ? "y" : "") + (z != null ? "z" : ""));
                }
            }
            if (near != null && arrive != Arrive.NEAR) {
                throw new IllegalArgumentException(GotoReminders.nearWithoutArriveNear(near));
            }
            if (arrive == Arrive.NEAR && near == null) {
                near = DEFAULT_NEAR;
            }
            if (cells == null && x == null && arrive != Arrive.AT) {
                throw new IllegalArgumentException(GotoReminders.heightTakesNoArrive(arrive.word()));
            }
            if (cells == null && y == null && (arrive == Arrive.USE || arrive == Arrive.DIG || arrive == Arrive.REACH)) {
                throw new IllegalArgumentException(GotoReminders.blockNeedsY(arrive.word()));
            }
        }

        /** 坐标的去处。 */
        public Stop(Integer x, Integer y, Integer z, Arrive arrive, Integer near) {
            this(x, y, z, null, arrive, near);
        }

        /**
         * 调用里读到的几样:一处或几格、{@code arrive}(没写是 {@code at})、{@code near}(没写为 null)。几格时每一处都得是一格。
         */
        public static Stop of(List<Place> places, String arriveWord, Integer near) {
            if (places.size() == 1) {
                Place place = places.get(0);
                return new Stop(place.x(), place.y(), place.z(), null, Arrive.of(arriveWord), near);
            }
            List<BlockPos> cells = new ArrayList<>();
            for (Place place : places) {
                if (place.cell() == null) {
                    throw new IllegalArgumentException("several places are several cells, each {x = …, y = …, z = …}; "
                            + place.literal() + " is " + (place.x() == null ? "a height" : "a column"));
                }
                cells.add(place.cell());
            }
            return new Stop(null, null, null, cells, Arrive.of(arriveWord), near);
        }

        /** 那一格(x、y、z 都给了时);否则为 null。 */
        public BlockPos cell() {
            return y != null && x != null ? new BlockPos(x, y, z) : null;
        }

        /**
         * 坐标的去处给人说"朝哪儿"的那一格:一格就是它,一列是那一列上与 {@code from} 同高的一格,一个高度是 {@code from} 那一列上
         * 的那个高度。几格的那一格见 {@link Destination#toward}。
         */
        BlockPos toward(BlockPos from) {
            if (x == null) {
                return new BlockPos(from.getX(), y, from.getZ());
            }
            return new BlockPos(x, y == null ? from.getY() : y, z);
        }

        /**
         * 给模型看的一截:{@code 120,64,-35}、{@code x=120 z=-35}、{@code y=64} 或 {@code 5 cells},不是 at 时接上怎样算到了。
         */
        public String words() {
            String where = cells != null ? cells.size() + " cells"
                    : x == null ? "y=" + y : y == null ? "x=" + x + " z=" + z : x + "," + y + "," + z;
            return switch (arrive) {
                case AT -> where;
                case USE -> where + (cells != null ? " (to use one of them)" : " (to use it)");
                case NEAR -> where + " (within " + near + ")";
                case DIG -> where + (cells != null ? " (to dig one of them)" : " (to dig it)");
                case REACH -> where + (cells != null ? " (to build into one of them)" : " (to build into it)");
            };
        }

        /** 给主人看的一句(头顶气泡、面板)。 */
        public String describe() {
            if (cells != null) {
                return switch (arrive) {
                    case AT -> "走进 " + cells.size() + " 格之一";
                    case USE -> "去用 " + cells.size() + " 格里的方块";
                    case NEAR -> "走到 " + cells.size() + " 格 " + near + " 格内";
                    case DIG -> "走到够得着 " + cells.size() + " 格里方块的地方";
                    case REACH -> "走到够得着 " + cells.size() + " 格里一格、往里放方块的地方";
                };
            }
            String where = x == null ? "y=" + y : y == null ? "x=" + x + " z=" + z : x + "," + y + "," + z;
            return switch (arrive) {
                case AT -> x == null ? "到 " + where : "走向 " + where;
                case USE -> "去用 " + where;
                case NEAR -> "走到 " + where + " " + near + " 格内";
                case DIG -> "走到够得着 " + where + " 的地方";
                case REACH -> "走到够得着 " + where + "、往里放方块的地方";
            };
        }
    }

    /**
     * 按此刻的世界把写法编成去处;写错了抛出带提醒的 {@link IllegalArgumentException}(受理回执就是这句话)。
     *
     * @param spec 走到这里的路线规格:许改地形时,站不进去、站不上去的格由寻路去挖、去垫,不算写错
     * @param from 从哪儿去:坐标缺的那一截照它补("朝哪儿"那一格),几格按离它的远近挑成员
     */
    public static Destination of(NumenPlayer her, Stop stop, RouteSpec spec, BlockPos from) {
        if (stop.cells() != null) {
            return cells(her, stop, spec, from);
        }
        Integer x = stop.x();
        Integer y = stop.y();
        Integer z = stop.z();
        Goals.Position position = new Goals.Position(x, y, z);
        Terrain terrain = Terrain.of(her);
        BlockPos cell = stop.cell();
        boolean alters = spec.alter().mayAlter();
        Goal goal = switch (stop.arrive()) {
            case AT -> {
                if (cell != null && !alters && !terrain.standable(cell)) {
                    throw new IllegalArgumentException(terrain.fits(x, y, z)
                            ? GotoReminders.midAir(cell, ground(terrain, cell))
                            : GotoReminders.occupied(cell, NavText.name(terrain.state(cell)),
                                    terrain.standingOn(cell)));
                }
                yield position;
            }
            case USE -> use(her, terrain, cell);
            case NEAR -> Goals.within(position, 0, stop.near());
            case DIG -> dig(her, terrain, cell);
            case REACH -> reach(her, cell);
        };
        return new Destination(stop, goal, stop.toward(from));
    }

    /** 给人说"朝哪儿"的那一格:几格是离 {@code from} 最近的一格;坐标照 {@link Stop#toward}。 */
    public static BlockPos toward(Stop stop, BlockPos from) {
        return stop.cells() == null ? stop.toward(from) : Cells.of(stop.cells()).nearest(from);
    }

    /** 去几格之一:在离出发点最近的那些里挑成员,按到达方式编成"多个取其一"(见类说明)。 */
    private static Destination cells(NumenPlayer her, Stop stop, RouteSpec spec, BlockPos from) {
        Cells given = Cells.of(stop.cells());
        List<BlockPos> nearest = given.nearest(from, NEAREST);
        Terrain terrain = Terrain.of(her);
        long cells = given.size();
        List<Goal> members = new ArrayList<>();
        BlockPos toward = null;
        switch (stop.arrive()) {
            case AT -> {
                boolean alters = spec.alter().mayAlter();
                for (BlockPos cell : nearest) {
                    // 许改地形时站不进去的格由寻路去挖、去垫;没加载的列此刻判不了,留给走到那儿时的规划
                    if (alters || !terrain.loaded(cell.getX(), cell.getZ()) || terrain.standable(cell)) {
                        members.add(Goals.at(cell));
                        toward = toward == null ? cell : toward;
                        if (members.size() == MEMBERS) {
                            break;
                        }
                    }
                }
                if (members.isEmpty()) {
                    throw new IllegalArgumentException(GotoReminders.noneToStand(nearest.get(0), nearest.size(), cells));
                }
            }
            case USE -> {
                int tried = 0;
                BlockPos firstTried = null;
                for (BlockPos cell : nearest) {
                    if (!terrain.clickable(cell)) {
                        continue;
                    }
                    Goals.Use use = terrain.use(cell);
                    firstTried = firstTried == null ? cell : firstTried;
                    tried++;
                    if (!use.sealed() && !use.stands().isEmpty()) {
                        members.add(use);
                        toward = toward == null ? cell : toward;
                    }
                    if (members.size() == USE_MEMBERS || tried == USE_TRIES) {
                        break;
                    }
                }
                if (firstTried == null) {
                    throw new IllegalArgumentException(GotoReminders.noneToUse(nearest.get(0), nearest.size(), cells));
                }
                if (members.isEmpty()) {
                    throw new IllegalArgumentException(GotoReminders.noneUsable(nearest.get(0), tried, firstTried));
                }
            }
            case NEAR -> {
                for (BlockPos cell : nearest.subList(0, Math.min(MEMBERS, nearest.size()))) {
                    members.add(Goals.within(Goals.at(cell), 0, stop.near()));
                }
                toward = nearest.get(0);
            }
            case REACH -> {
                for (BlockPos cell : nearest.subList(0, Math.min(MEMBERS, nearest.size()))) {
                    members.add(reach(her, cell));
                }
                toward = nearest.get(0);
            }
            case DIG -> {
                DigQuote pricing = DigQuote.of(her, DigTaskRecord.TARGET_SPEC);
                DigQuote clearing = clearing(her);
                List<Goals.DigTarget> targets = new ArrayList<>();
                String firstWhy = null;
                for (BlockPos cell : nearest) {
                    // 没加载的列此刻判不了,留给走到那儿时的规划;空气与流体没有可挖的;挖不成的不去
                    boolean loaded = terrain.loaded(cell.getX(), cell.getZ());
                    if (loaded && !terrain.clickable(cell)) {
                        continue;
                    }
                    String why = loaded ? undiggable(pricing, clearing, terrain, cell) : null;
                    if (why != null) {
                        firstWhy = firstWhy == null ? why : firstWhy;
                        continue;
                    }
                    // 挖它本身的价钱与 work dig 挑目标时同一个报价;没加载的列此刻读不到,不另收
                    targets.add(new Goals.DigTarget(cell,
                            loaded ? pricing.price(cell, terrain.state(cell)).cost() : 0));
                    toward = toward == null ? cell : toward;
                    if (targets.size() == MEMBERS) {
                        break;
                    }
                }
                if (targets.isEmpty()) {
                    throw new IllegalArgumentException(firstWhy != null
                            ? GotoReminders.noneDiggable(nearest.size(), cells, firstWhy)
                            : GotoReminders.noneToDig(nearest.get(0), nearest.size(), cells));
                }
                return new Destination(stop, Goals.dig(targets, Snapshots.stats(her), clearing.clearing()), toward);
            }
        }
        return new Destination(stop, members.size() == 1 ? members.get(0) : Goals.anyOf(members), toward);
    }

    /** 用一格方块的目标;没有可点的轮廓、四面封死、够得着的地方一处也站不了,都当场提醒。 */
    private static Goals.Use use(NumenPlayer her, Terrain terrain, BlockPos cell) {
        String block = NavText.name(terrain.state(cell));
        if (!terrain.clickable(cell)) {
            throw new IllegalArgumentException(GotoReminders.nothingToUse(cell, block));
        }
        Goals.Use use = terrain.use(cell);
        if (use.sealed()) {
            List<GotoReminders.Cover> covers = new ArrayList<>();
            for (Direction side : Direction.values()) {
                BlockPos front = cell.relative(side);
                covers.add(new GotoReminders.Cover(side.getName(), NavText.name(terrain.state(front)), front));
            }
            covers.sort(Comparator.comparingDouble(c -> c.at().distToCenterSqr(her.getEyePosition())));
            throw new IllegalArgumentException(GotoReminders.sealed(cell, block, covers));
        }
        if (use.stands().isEmpty()) {
            throw new IllegalArgumentException(GotoReminders.nowhereToStand(cell, block,
                    use.open().stream().map(Direction::getName).toList()));
        }
        return use;
    }

    /**
     * 往一格里放方块的目标:手够得着它、身体不占着它。{@code numen.build.place} 判"这一格够不够得着"问的也是它,走到了就放得了。
     */
    public static Goal reach(NumenPlayer her, BlockPos cell) {
        return Goals.place(cell, Snapshots.stats(her));
    }

    /** 挖一格方块的目标;空气、流体没有可挖的,站到哪儿 {@code numen.work.dig} 都挖不成的({@link #undiggable}),都当场提醒。 */
    private static Goal dig(NumenPlayer her, Terrain terrain, BlockPos cell) {
        if (!terrain.clickable(cell)) {
            throw new IllegalArgumentException(GotoReminders.nothingToDig(cell, NavText.name(terrain.state(cell))));
        }
        DigQuote clearing = clearing(her);
        String why = undiggable(DigQuote.of(her, DigTaskRecord.TARGET_SPEC), clearing, terrain, cell);
        if (why != null) {
            throw new IllegalArgumentException(why + ".");
        }
        return Goals.dig(cell, Snapshots.stats(her), clearing.clearing());
    }

    /**
     * 站到哪儿 {@code numen.work.dig} 都挖不成 {@code cell} 的缘由;挖得成为 null。问的是 {@code numen.work.dig} 挑目标时问的同几件事:按
     * {@link DigTaskRecord#TARGET_SPEC} 挖它进不进得了(物理上挖不挖得动、规则许不许),每一面是不是都贴着清不掉的方块。
     */
    private static String undiggable(DigQuote pricing, DigQuote clearing, Terrain terrain, BlockPos cell) {
        String refused = pricing.uncleared(cell);
        if (refused != null) {
            return GotoReminders.cantDig(cell, NavText.name(terrain.state(cell)), refused);
        }
        return clearing.walledIn(cell);
    }

    /**
     * 到了之后 {@code numen.work.dig} 清得掉哪些挡着视线的格:它清遮挡用的那份规格({@link DigTaskRecord#SPEC}),按此刻的身体与许可。
     * 走路许不许改地形是这一趟自己的事,与它无关。
     */
    private static DigQuote clearing(NumenPlayer her) {
        return DigQuote.of(her, DigTaskRecord.SPEC);
    }

    /** 那一列里往下第一个站得住的节点;一直到底都没有为 null。 */
    private static BlockPos ground(Terrain terrain, BlockPos cell) {
        BlockPos settled = terrain.settle(cell);
        return terrain.standable(settled) ? settled : null;
    }
}
