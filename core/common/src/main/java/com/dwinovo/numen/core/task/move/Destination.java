package com.dwinovo.numen.core.task.move;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import com.dwinovo.numen.core.nav.NavText;
import com.dwinovo.numen.core.nav.Terrain;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code move goto} 与 {@code move route} 的去处:坐标(给几个算几个)加怎样算到了({@code --arrive at|use|near},
 * {@code near} 只配 {@code near})。参数名一一对应到寻路模块的目标,只在这里对应:
 * <ul>
 *   <li>{@code at}:位置——{@code x y z} 是那一格,{@code x z} 是那一列,只给 {@code y} 是那个高度({@link Goals#at}、
 *       {@link Goals#column}、{@link Goals#level})。站到一块方块上面也是 {@code at}:坐标是它上面脚所在的那一格;</li>
 *   <li>{@code use}:用那一格方块——站在它敞开的面前、看得见、点得到({@link Goals#use});</li>
 *   <li>{@code near}:离那一格(或那一列)不超过 {@code near} 格({@link Goals#within})。</li>
 * </ul>
 * 写错了受理当场提醒({@link GotoReminders}),不替她改写、不去搜索;要不要提醒一律问模块({@link Terrain}),这里不另判。
 *
 * @param x      没给为 null
 * @param y      没给为 null
 * @param z      没给为 null
 * @param arrive 怎样算到了
 * @param near   {@code arrive=near} 时的距离,否则为 null
 * @param goal   编好的目标;{@code use} 的候选站位按受理那一刻的世界列定
 */
public record Destination(Integer x, Integer y, Integer z, Arrive arrive, Integer near, Goal goal) {

    /** 怎样算到了。 */
    public enum Arrive {
        AT, USE, NEAR;

        /** 命令行上的写法。 */
        public String word() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Arrive of(String word) {
            return word == null ? AT : valueOf(word.toUpperCase(Locale.ROOT));
        }
    }

    /** 命令行上 {@code --arrive} 能写的几个。 */
    public static final String[] ARRIVE_WORDS = {"at", "use", "near"};

    /**
     * 按受理那一刻的世界把参数编成去处;写错了抛出带提醒的 {@link IllegalArgumentException}(受理回执就是这句话)。
     *
     * @param spec 这一趟的路线规格:许改地形时,站不进去、站不上去的格由寻路去挖、去垫,不算写错
     */
    public static Destination of(ServerPlayer her, Integer x, Integer y, Integer z, String arriveWord, Integer near,
                                 RouteSpec spec) {
        Arrive arrive = Arrive.of(arriveWord);
        boolean hasXz = x != null && z != null;
        if ((x == null) != (z == null) || (!hasXz && y == null)) {
            throw new IllegalArgumentException("move_goto needs x and z (a place), x, y and z (one cell), y alone"
                    + " (a height), or route alone (a planned route); got " + (x != null ? "x" : "")
                    + (y != null ? "y" : "") + (z != null ? "z" : ""));
        }
        if (near != null && arrive != Arrive.NEAR) {
            throw new IllegalArgumentException(GotoReminders.nearWithoutArriveNear(near));
        }
        if (arrive == Arrive.NEAR && near == null) {
            throw new IllegalArgumentException(GotoReminders.arriveNearWithoutNear());
        }
        if (!hasXz && arrive != Arrive.AT) {
            throw new IllegalArgumentException(GotoReminders.heightTakesNoArrive(arrive.word()));
        }
        if (y == null && arrive == Arrive.USE) {
            throw new IllegalArgumentException(GotoReminders.blockNeedsY(arrive.word()));
        }
        Goals.Position position = new Goals.Position(x, y, z);
        Terrain terrain = Terrain.of(her);
        BlockPos cell = position.cell() ? new BlockPos(x, y, z) : null;
        boolean alters = spec.alter().mayAlter();
        Goal goal = switch (arrive) {
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
            case NEAR -> Goals.within(position, 0, near);
        };
        return new Destination(x, y, z, arrive, near, goal);
    }

    /** 用一格方块的目标;没有可点的轮廓、四面封死、够得着的地方一处也站不了,都当场提醒。 */
    private static Goals.Use use(ServerPlayer her, Terrain terrain, BlockPos cell) {
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

    /** 那一列里往下第一个站得住的节点;一直到底都没有为 null。 */
    private static BlockPos ground(Terrain terrain, BlockPos cell) {
        BlockPos settled = terrain.settle(cell);
        return terrain.standable(settled) ? settled : null;
    }

    /** 那一格(x、y、z 都给了时)。 */
    public BlockPos cell() {
        return y != null && x != null ? new BlockPos(x, y, z) : null;
    }

    /**
     * 给人说"朝哪儿"的那一格(回执里的方向与距离):一格就是它,一列是那一列上与 {@code from} 同高的一格,一个高度是
     * {@code from} 那一列上的那个高度。
     */
    public BlockPos toward(BlockPos from) {
        if (x == null) {
            return new BlockPos(from.getX(), y, from.getZ());
        }
        return new BlockPos(x, y == null ? from.getY() : y, z);
    }

    /** 给主人看的一句(头顶气泡、面板)。 */
    public String describe() {
        String where = x == null ? "y=" + y : y == null ? "x=" + x + " z=" + z : x + "," + y + "," + z;
        return switch (arrive) {
            case AT -> x == null ? "到 " + where : "走向 " + where;
            case USE -> "去用 " + where;
            case NEAR -> "走到 " + where + " " + near + " 格内";
        };
    }
}
