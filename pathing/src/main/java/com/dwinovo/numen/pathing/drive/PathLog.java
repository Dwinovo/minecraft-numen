package com.dwinovo.numen.pathing.drive;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

import com.dwinovo.numen.pathing.plan.Maneuver;
import com.dwinovo.numen.pathing.plan.MoveKind;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * 寻路的日志:一行一件事,前缀 {@code [numen-path]},接着是谁(身体名加 UUID 前六位),再是事件与事实——坐标、方块 id(带状态)、
 * 数字都写齐。直接写进 slf4j 的 {@code NumenPathing} 这一个记录器,开多少由宿主的日志配置定:
 * <ul>
 *   <li>INFO——结局与被叫停、每一次搜索与规划的结论、重搜、一步走不下去、卡住、计划内的坠落与落地、倒水接坠落与收水、
 *       动手被拒、下载具、不在推进、撤垫块的起止与留下的块;</li>
 *   <li>DEBUG——出发、派搜索、路线上的每一步、每一步开始、每一下成功的挖与放与开关门、换目标、暂停、身体换手上的东西;</li>
 *   <li>WARN——主线程上寻路一刻用的时间超过 {@link #MAIN_THREAD_WARN_NANOS}。</li>
 * </ul>
 * 事件只在发生时记一次,不逐刻刷。
 *
 * <p>没有做成宿主实现的端口:宿主对日志的要求只是"进服务器日志、能按级别与记录器开关",slf4j 加宿主自己的日志配置
 * 本来就是这个出口,原版与加载器都经它记;端口只会让每个宿主(连同 GameTest 的假玩家夹具)多写一份转发。
 */
public final class PathLog {

    private static final Logger LOG = LoggerFactory.getLogger("NumenPathing");
    private static final String PREFIX = "[numen-path] ";

    /**
     * 主线程上一次导航一刻里寻路做的活(拷快照、组成本模型、复核前提、按键与转头)超过这么多纳秒,就记 WARN。
     * 依据:原版每刻 50 毫秒,这是它的十分之一,一刻里别的实体与区块还要用剩下的;实测拷一次快照 0.1–1 毫秒,复核一步只判
     * 一次前提(搜索展开一个节点要判二十来次、共 15–76 微秒,docs/pathing.md 第十三节),平常远在它之下。按墙钟计,不随 {@code /tick rate} 变:刻速调高时一刻的预算变小,
     * 服务器跟不上是刻速的事;这里只标出寻路自己在主线程上做了重活的那几刻。
     */
    static final long MAIN_THREAD_WARN_NANOS = 5_000_000L;

    private PathLog() {}

    public static void info(String format, Object... args) {
        LOG.info(PREFIX + format, args);
    }

    public static void debug(String format, Object... args) {
        if (LOG.isDebugEnabled()) {
            LOG.debug(PREFIX + format, args);
        }
    }

    public static void warn(String format, Object... args) {
        LOG.warn(PREFIX + format, args);
    }

    /** DEBUG 开着:拼一行要花点工夫的(整条路线)先问它。 */
    public static boolean debugging() {
        return LOG.isDebugEnabled();
    }

    /** 主线程上一次做完的寻路活 {@code what} 用了 {@code nanos}:超过 {@link #MAIN_THREAD_WARN_NANOS} 记 WARN。 */
    public static void mainThread(String who, String what, long nanos) {
        if (nanos > MAIN_THREAD_WARN_NANOS) {
            warn("{} 主线程上{}用了 {}(阈值 {})", who, what, ms(nanos), ms(MAIN_THREAD_WARN_NANOS));
        }
    }

    // ==================== 事实的写法 ====================

    /** 谁:身体名,加 UUID 前六位(同名的身体分得开)。 */
    public static String who(ServerPlayer body) {
        return body.getScoreboardName() + "#" + body.getStringUUID().substring(0, 6);
    }

    /** 一格:{@code x,y,z}。 */
    public static String pos(BlockPos pos) {
        return pos == null ? "-" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 身体的精确位置,两位小数。 */
    public static String at(Vec3 v) {
        return String.format(Locale.ROOT, "%.2f,%.2f,%.2f", v.x, v.y, v.z);
    }

    /** 方块:注册名,有状态时连同状态,与 {@code /setblock} 的写法相同。 */
    public static String block(BlockState state) {
        return BlockStateParser.serialize(state);
    }

    /** 纳秒写成毫秒,一位小数。 */
    public static String ms(long nanos) {
        return String.format(Locale.ROOT, "%.1fms", nanos / 1.0e6);
    }

    /** 纳秒写成秒,一位小数。 */
    public static String seconds(long nanos) {
        return String.format(Locale.ROOT, "%.1fs", nanos / 1.0e9);
    }

    /** 一个数,一位小数。 */
    public static String num(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    /** 规格的要点:改地形的级别,与出厂值不同的上限与开关。 */
    public static String spec(RouteSpec spec) {
        StringBuilder out = new StringBuilder("alter=").append(spec.alter().name().toLowerCase(Locale.ROOT));
        out.append(" maxFall=").append(spec.maxFallHeightNoWater());
        if (spec.budgeted()) {
            out.append(" alterBudget=").append(spec.alterBudget());
        }
        if (spec.parkour()) {
            out.append(" parkour");
        }
        if (spec.takeBack()) {
            out.append(" takeBack");
        }
        return out.toString();
    }

    /** 一条路线的要点:几步、各种走法几步、总代价、改几格、终点。 */
    public static String route(Route route) {
        Map<MoveKind, Integer> kinds = new EnumMap<>(MoveKind.class);
        for (Route.Leg leg : route.legs()) {
            kinds.merge(leg.maneuver().kind(), 1, Integer::sum);
        }
        StringBuilder out = new StringBuilder().append(route.legs().size()).append(" 步 [");
        boolean first = true;
        for (Map.Entry<MoveKind, Integer> e : kinds.entrySet()) {
            out.append(first ? "" : " ").append(e.getKey()).append('×').append(e.getValue());
            first = false;
        }
        return out.append("] 代价 ").append(num(route.cost())).append(" 改 ").append(route.alterations())
                .append(" 格 ").append(pos(route.start())).append("→").append(pos(route.end())).toString();
    }

    /** 路线上的每一步:走法与落点,排障时还原她走的是哪条路。 */
    public static String legs(Route route) {
        StringBuilder out = new StringBuilder(pos(route.start()));
        for (Route.Leg leg : route.legs()) {
            Maneuver m = leg.maneuver();
            out.append(' ').append(m.kind()).append('>').append(pos(m.to()));
        }
        return out.toString();
    }

    /** 一步:走法、起落点。 */
    public static String step(Maneuver m) {
        return m.kind() + " " + pos(m.from()) + "→" + pos(m.to());
    }

    /** 一步走不下去:哪种走法、哪一格、什么方块、哪条前提或执行里出的事。 */
    public static String blockage(Blockage b) {
        return b.move() + " 卡在 " + pos(b.cell()) + " " + block(b.block()) + " "
                + (b.reason() != null ? "前提=" + b.reason() : "执行=" + b.hitch());
    }

    /** 身体此刻的状态:精确位置、在地上还是空中、泡没泡水、眼睛在不在水里、氧气、血量。 */
    public static String body(ServerPlayer body) {
        return "身体 " + at(body.position()) + (body.onGround() ? " 地上" : " 空中") + (body.isInWater() ? " 水里" : "")
                + (body.isEyeInFluid(net.minecraft.tags.FluidTags.WATER) ? " 眼在水里" : "")
                + " 氧气 " + body.getAirSupply() + "/" + body.getMaxAirSupply() + " 血 " + num(body.getHealth());
    }
}
