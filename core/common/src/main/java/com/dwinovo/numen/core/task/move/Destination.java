package com.dwinovo.numen.core.task.move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dwinovo.numen.core.nav.DigQuote;
import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.core.route.Stop;
import com.dwinovo.numen.core.route.Target;
import com.dwinovo.numen.core.task.dig.DigTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;

/**
 * 去处:路线描述里的一站({@link Stop})按那一刻的世界编成的寻路目标。写法到目标的对应只在这里:
 * <ul>
 *   <li>{@code at}:位置——一格、一列、一个高度({@link Goals#at}、{@link Goals#column}、{@link Goals#level});站到一块方块上面
 *       也是 {@code at}:坐标是它上面脚所在的那一格;</li>
 *   <li>{@code near}:离它不超过 {@code range} 格({@link Goals#within});</li>
 *   <li>{@code use}:用那一格方块——站在它敞开的面前、看得见、点得到({@link Goals#use});</li>
 *   <li>{@code dig}:挖那一格方块——站到手够得着它、身体不占着它、挡着视线的都是 {@code numen.work.dig} 清得掉的地方
 *       ({@link Goals#dig},清不清得掉按 {@link #clearing} 问),那一格本身留给 {@code numen.work.dig};</li>
 *   <li>{@code place}:往那一格里放——手够得着它、身体不占着它({@link Goals#place});</li>
 *   <li>{@code away}:离它至少 {@code range} 格({@link Goals#within} 的最大是无穷)。</li>
 * </ul>
 * 一只实体按编目标那一刻它所在的那一格算。一堆格子(一团矿)对每一种到达都成立:{@code at}、{@code near}、{@code use}、
 * {@code place} 是到了其中任何一格就算({@link Goals#anyOf});{@code dig} 是够得着其中任意一个 {@code numen.work.dig} 挖得成的方块,
 * 同样划算的站位里优先一次够得着最多格的({@link Goals#dig(List, com.dwinovo.numen.pathing.world.BodyStats, Goals.Clearing)});
 * {@code away} 是离其中每一格都够远({@link Goals#allOf})。成员有界:只取离出发点最近的 {@link #MEMBERS} 格({@code use} 至多
 * {@link #USE_MEMBERS} 个)。
 *
 * <p>这一趟走不通的写法(站不进去、半空、没东西可用、四面封死)当场以带提醒的 {@link IllegalArgumentException} 交出,规划把它写进
 * 那一段的为什么;要不要提醒一律问模块({@link Terrain}),这里不另判。
 *
 * @param goal   编好的目标;{@code use} 的候选站位按编的那一刻的世界列定
 * @param toward 给人说"朝哪儿"的那一格(回执里的方向与距离)
 */
public record Destination(Stop stop, Goal goal, BlockPos toward) {

    /** 一堆格子至多取几个成员:估价与判到没到逐个问成员,几十个仍是一次比较的量级。 */
    static final int MEMBERS = 64;
    /** {@code use} 至多几个成员。 */
    static final int USE_MEMBERS = 8;
    /** {@code use} 至多为几个能点的方块列候选站位:每列一个都要在它周围一两千个节点上打射线。 */
    static final int USE_TRIES = 16;

    /**
     * 按此刻的世界把一站编成去处;这一趟走不通的写法抛出带提醒的 {@link IllegalArgumentException}。
     *
     * @param spec 走到这里的规格:许挖许放时,站不进去、站不上去的格由寻路去挖、去垫,不算走不通
     * @param from 从哪儿去:坐标缺的那一截照它补("朝哪儿"那一格),一堆格子按离它的远近挑成员
     */
    public static Destination of(NumenPlayer her, Stop stop, RouteSpec spec, BlockPos from) {
        return switch (stop.to()) {
            case Target.Cells cells -> cells(her, stop, spec, cells.cells(), from);
            case Target.Cell cell -> cell(her, stop, spec, cell.pos());
            case Target.Mob mob -> {
                Entity entity = mob.ref().in(her.serverLevel());
                if (entity == null || entity == her) {
                    throw new IllegalArgumentException("no entity with id " + mob.ref() + " is here — it died, left, "
                            + "or ids changed with a restart; numen.scan.entities() lists who is around");
                }
                yield cell(her, stop, spec, entity.blockPosition());
            }
            case Target.Column column -> {
                Goals.Position position = Goals.column(column.x(), column.z());
                yield new Destination(stop, ranged(stop, position), new BlockPos(column.x(), from.getY(), column.z()));
            }
            case Target.Height height -> new Destination(stop, Goals.level(height.y()),
                    new BlockPos(from.getX(), height.y(), from.getZ()));
        };
    }

    /** {@code at}、{@code near}、{@code away} 对一个位置:正好在、不超过 {@code range}、至少 {@code range}。 */
    private static Goal ranged(Stop stop, Goals.Position position) {
        return switch (stop.arrive()) {
            case NEAR -> Goals.within(position, 0, stop.range());
            case AWAY -> Goals.within(position, stop.range(), Double.POSITIVE_INFINITY);
            default -> position;
        };
    }

    /** 一格的去处。 */
    private static Destination cell(NumenPlayer her, Stop stop, RouteSpec spec, BlockPos cell) {
        Terrain terrain = Terrain.of(her);
        Goal goal = switch (stop.arrive()) {
            case AT -> {
                if (!spec.changes() && !terrain.standable(cell)) {
                    throw new IllegalArgumentException(terrain.fits(cell.getX(), cell.getY(), cell.getZ())
                            ? GotoReminders.midAir(cell, ground(terrain, cell))
                            : GotoReminders.occupied(cell, NavText.name(terrain.state(cell)),
                                    terrain.standingOn(cell)));
                }
                yield Goals.at(cell);
            }
            case NEAR, AWAY -> ranged(stop, Goals.at(cell));
            case USE -> use(her, terrain, cell);
            case DIG -> dig(her, terrain, cell);
            case PLACE -> reach(her, cell);
        };
        return new Destination(stop, goal, cell);
    }

    /** 一堆格子:按离出发点的远近挑成员,按到达方式编成"多个取其一"(见类说明)。 */
    private static Destination cells(NumenPlayer her, Stop stop, RouteSpec spec, List<BlockPos> all, BlockPos from) {
        List<BlockPos> nearest = all.stream().sorted(Comparator.comparingDouble(c -> c.distSqr(from))).toList();
        Terrain terrain = Terrain.of(her);
        List<Goal> members = new ArrayList<>();
        BlockPos toward = nearest.get(0);
        switch (stop.arrive()) {
            case AT -> {
                toward = null;
                for (BlockPos cell : nearest) {
                    // 许挖许放时站不进去的格由寻路去挖、去垫;没加载的列此刻判不了,留给走到那儿时的规划
                    if (spec.changes() || !terrain.loaded(cell.getX(), cell.getZ()) || terrain.standable(cell)) {
                        members.add(Goals.at(cell));
                        toward = toward == null ? cell : toward;
                        if (members.size() == MEMBERS) {
                            break;
                        }
                    }
                }
                if (members.isEmpty()) {
                    throw new IllegalArgumentException(GotoReminders.cellsNowhereToStand(nearest.size()));
                }
            }
            case NEAR, AWAY, PLACE -> {
                for (BlockPos cell : nearest.subList(0, Math.min(MEMBERS, nearest.size()))) {
                    members.add(stop.arrive() == Stop.Arrive.PLACE ? reach(her, cell) : ranged(stop, Goals.at(cell)));
                }
                if (stop.arrive() == Stop.Arrive.AWAY) {
                    return new Destination(stop, Goals.allOf(members), toward);
                }
            }
            case USE -> {
                int tried = 0;
                BlockPos firstTried = null;
                toward = null;
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
                    throw new IllegalArgumentException(GotoReminders.cellsNothingToUse(nearest.size()));
                }
                if (members.isEmpty()) {
                    throw new IllegalArgumentException(GotoReminders.cellsNoneUsable(tried, firstTried));
                }
            }
            case DIG -> {
                DigQuote pricing = DigQuote.of(her, DigTaskRecord.TARGET_SPEC);
                DigQuote clearing = clearing(her);
                List<Goals.DigTarget> targets = new ArrayList<>();
                String firstWhy = null;
                toward = null;
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
                    // 挖它本身的价钱与 numen.work.dig 挑目标时同一个报价;没加载的列此刻读不到,不另收
                    targets.add(new Goals.DigTarget(cell,
                            loaded ? pricing.price(cell, terrain.state(cell)).cost() : 0));
                    toward = toward == null ? cell : toward;
                    if (targets.size() == MEMBERS) {
                        break;
                    }
                }
                if (targets.isEmpty()) {
                    throw new IllegalArgumentException(firstWhy != null
                            ? GotoReminders.cellsNoneDiggable(nearest.size(), firstWhy)
                            : GotoReminders.cellsNothingToDig(nearest.size()));
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
     * 走路许不许挖是这一趟自己的事,与它无关。
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
