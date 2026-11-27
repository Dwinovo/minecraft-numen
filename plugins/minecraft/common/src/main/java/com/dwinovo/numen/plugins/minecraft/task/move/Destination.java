package com.dwinovo.numen.plugins.minecraft.task.move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.dwinovo.numen.plugins.minecraft.nav.DigQuote;
import com.dwinovo.numen.plugins.minecraft.nav.NavText;
import com.dwinovo.numen.plugins.minecraft.nav.Terrain;
import com.dwinovo.numen.plugins.minecraft.task.dig.DigTaskRecord;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.plugins.minecraft.pathing.api.Snapshots;
import com.dwinovo.numen.plugins.minecraft.pathing.search.Goal;
import com.dwinovo.numen.plugins.minecraft.pathing.search.Goals;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/**
 * 去处:一个到达目标({@link Arrival})按那一刻的世界编成的寻路目标。写法到目标的对应只在这里:
 * <ul>
 *   <li>{@code at}:位置——一格、一列、一个高度({@link Goals#at}、{@link Goals#column}、{@link Goals#level});站到一块方块上面
 *       也是 {@code at}:坐标是它上面脚所在的那一格。走路可挖可搭,站不进去的格由寻路去挖、去垫;</li>
 *   <li>{@code near}:离它不超过 {@code range} 格({@link Goals#within});</li>
 *   <li>{@code use}:用那一格——站到手够得着它、身体不占着它的地方({@link Goals#reach}),那一格里有没有东西不问;</li>
 *   <li>{@code dig}:挖那一格方块——同一种到达({@link Goals#reach}),那一格得是 {@code minecraft.work.dig} 挖得成的方块,
 *       它本身留给 {@code minecraft.work.dig};</li>
 *   <li>{@code away}:离它至少 {@code range} 格({@link Goals#within} 的最大是无穷)。</li>
 * </ul>
 * 一只实体按编目标那一刻它所在的那一格算。一堆格子(一团矿)对每一种到达都成立:{@code at}、{@code near}、{@code use}
 * 是到了其中任何一格就算({@link Goals#anyOf});{@code dig} 是够得着其中任意一个 {@code minecraft.work.dig} 挖得成的方块,
 * 同样划算的站位里优先一次够得着最多格的({@link Goals#dig(List, com.dwinovo.numen.plugins.minecraft.pathing.world.BodyStats)});
 * {@code away} 是离其中每一格都够远({@link Goals#allOf})。成员有界:只取离出发点最近的 {@link #MEMBERS} 格。
 *
 * <p>这一趟走不通的写法(没东西可挖、点名的实体不在了)当场以带提醒的 {@link IllegalArgumentException} 交出;要不要提醒
 * 一律问模块({@link Terrain}),这里不另判。
 *
 * @param goal   编好的目标
 * @param toward 给人说"朝哪儿"的那一格(回执里的方向与距离)
 */
public record Destination(Arrival arrival, Goal goal, BlockPos toward) {

    /** 点名的实体不在了(死了、走了、被捡走了):不是路的事,是 not_found。 */
    public static final class Gone extends IllegalArgumentException {
        Gone(String message) {
            super(message);
        }
    }

    /** 一堆格子至多取几个成员:估价与判到没到逐个问成员,几十个仍是一次比较的量级。 */
    static final int MEMBERS = 64;

    /**
     * 按此刻的世界把一个到达目标编成去处;这一趟走不通的写法抛出带提醒的 {@link IllegalArgumentException}。
     *
     * @param from 从哪儿去:坐标缺的那一截照它补("朝哪儿"那一格),一堆格子按离它的远近挑成员
     */
    public static Destination of(NumenPlayer her, Arrival arrival, BlockPos from) {
        return switch (arrival.to()) {
            case Where.Cells cells -> cells(her, arrival, cells.cells(), from);
            case Where.Cell cell -> cell(her, arrival, cell.pos());
            case Where.Mob mob -> {
                Entity entity = mob.ref().in(her.serverLevel());
                if (entity == null || entity == her) {
                    throw new Gone(mob.ref() + " is not near you — it died, left, or is in an unloaded chunk or another "
                            + "dimension; minecraft.scan.entities() lists who is around");
                }
                if (arrival.how() == Arrival.How.BESIDE && entity instanceof ItemEntity item) {
                    yield new Destination(arrival, new ItemReach(item.getBoundingBox()), item.blockPosition());
                }
                yield cell(her, arrival, entity.blockPosition());
            }
            case Where.Column column -> {
                Goals.Position position = Goals.column(column.x(), column.z());
                yield new Destination(arrival, ranged(arrival, position),
                        new BlockPos(column.x(), from.getY(), column.z()));
            }
            case Where.Height height -> new Destination(arrival, Goals.level(height.y()),
                    new BlockPos(from.getX(), height.y(), from.getZ()));
        };
    }

    /** {@code at}、{@code near}、{@code away} 对一个位置:正好在、不超过 {@code range}、至少 {@code range}。 */
    private static Goal ranged(Arrival arrival, Goals.Position position) {
        return switch (arrival.how()) {
            case NEAR, BESIDE -> Goals.within(position, 0, arrival.range());
            case AWAY -> Goals.within(position, arrival.range(), Double.POSITIVE_INFINITY);
            default -> position;
        };
    }

    /** 一格的去处。 */
    private static Destination cell(NumenPlayer her, Arrival arrival, BlockPos cell) {
        Terrain terrain = Terrain.of(her);
        Goal goal = switch (arrival.how()) {
            case AT -> Goals.at(cell);
            case NEAR, BESIDE, AWAY -> ranged(arrival, Goals.at(cell));
            case USE -> Goals.reach(cell, Snapshots.stats(her));
            case DIG -> dig(her, terrain, cell);
        };
        return new Destination(arrival, goal, cell);
    }

    /** 一堆格子:按离出发点的远近挑成员,按到达方式编成"多个取其一"(见类说明)。 */
    private static Destination cells(NumenPlayer her, Arrival arrival, List<BlockPos> all, BlockPos from) {
        List<BlockPos> nearest = all.stream().sorted(Comparator.comparingDouble(c -> c.distSqr(from))).toList();
        Terrain terrain = Terrain.of(her);
        List<Goal> members = new ArrayList<>();
        BlockPos toward = nearest.get(0);
        switch (arrival.how()) {
            case AT, NEAR, BESIDE, USE, AWAY -> {
                for (BlockPos cell : nearest.subList(0, Math.min(MEMBERS, nearest.size()))) {
                    members.add(switch (arrival.how()) {
                        case AT -> Goals.at(cell);
                        case USE -> Goals.reach(cell, Snapshots.stats(her));
                        default -> ranged(arrival, Goals.at(cell));
                    });
                }
                if (arrival.how() == Arrival.How.AWAY) {
                    return new Destination(arrival, Goals.allOf(members), toward);
                }
            }
            case DIG -> {
                DigQuote pricing = DigQuote.of(her, DigTaskRecord.TARGET_SPEC);
                List<Goals.DigTarget> targets = new ArrayList<>();
                String firstWhy = null;
                toward = null;
                for (BlockPos cell : nearest) {
                    // 没加载的列此刻判不了,留给走到那儿时的规划;空气与流体没有可挖的;挖不成的不去
                    boolean loaded = terrain.loaded(cell.getX(), cell.getZ());
                    if (loaded && !terrain.clickable(cell)) {
                        continue;
                    }
                    String why = loaded ? undiggable(pricing, terrain, cell) : null;
                    if (why != null) {
                        firstWhy = firstWhy == null ? why : firstWhy;
                        continue;
                    }
                    // 挖它本身的价钱与 minecraft.work.dig 挑目标时同一个报价;没加载的列此刻读不到,不另收
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
                return new Destination(arrival, Goals.dig(targets, Snapshots.stats(her)), toward);
            }
        }
        return new Destination(arrival, members.size() == 1 ? members.get(0) : Goals.anyOf(members), toward);
    }

    /** 挖一格方块的目标;空气、流体没有可挖的,站到哪儿 {@code minecraft.work.dig} 都挖不成的({@link #undiggable}),都当场提醒。 */
    private static Goal dig(NumenPlayer her, Terrain terrain, BlockPos cell) {
        if (!terrain.clickable(cell)) {
            throw new IllegalArgumentException(GotoReminders.nothingToDig(cell, NavText.name(terrain.state(cell))));
        }
        String why = undiggable(DigQuote.of(her, DigTaskRecord.TARGET_SPEC), terrain, cell);
        if (why != null) {
            throw new IllegalArgumentException(why + ".");
        }
        return Goals.reach(cell, Snapshots.stats(her));
    }

    /**
     * 站到哪儿 {@code minecraft.work.dig} 都挖不成 {@code cell} 的缘由;挖得成为 null。问的是 {@code minecraft.work.dig} 挑目标时问的同一件事:按
     * {@link DigTaskRecord#TARGET_SPEC} 挖它进不进得了(物理上挖不挖得动、规则许不许)。
     */
    private static String undiggable(DigQuote pricing, Terrain terrain, BlockPos cell) {
        String refused = pricing.whyNot(cell);
        return refused == null ? null : GotoReminders.cantDig(cell, NavText.name(terrain.state(cell)), refused);
    }
}
