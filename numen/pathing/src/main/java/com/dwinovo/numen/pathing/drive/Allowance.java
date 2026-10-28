package com.dwinovo.numen.pathing.drive;

import java.util.concurrent.TimeUnit;

import com.dwinovo.numen.pathing.search.Deadline;
import com.dwinovo.numen.pathing.search.SearchClock;

/**
 * 一趟愿意付多少:每次搜索想多久、最多展开几个节点,一趟总共展开几个节点,和这一趟已经花了多少。
 *
 * <p><b>每次想多久看表</b>(表是宿主经端口给的 {@link SearchClock}),三种时限:
 * <ul>
 *   <li>首段({@link #FIRST_NANOS}):从身体脚下搜,身体站着等,时间到就交半程让她先动(Baritone 首段 0.5 s、mineflayer-pathfinder
 *       每刻 40 ms);</li>
 *   <li>接续:半程一交出就从半程终点接着搜,身体走到要等接续的那几步之前搜完就不停顿:时限是走到那里几何上最短要的时间减余量
 *       ({@link #MARGIN_NANOS}),封顶 {@link #NEXT_MAX_NANOS}(Baritone 走路时下一段 4 s),不低于首段;</li>
 *   <li>搜不出路线(时间到或节点上限用完、又截不出半程)就加时重搜:下一次的时限翻倍,直到搜出路线(Azalea 的加时重试)。</li>
 * </ul>
 * 一次搜索最多展开 {@link #SEARCH_NODES} 个节点:只防内存,和时钟无关。
 *
 * <p><b>放不放弃看节点</b>:一趟的收场只有一个判据——总预算(按展开的节点数)用完还没到;另外是搜索证明了到不了。总预算与起点到终点的
 * 估价成正比({@link #NODES_PER_TICK}),至少够一次搜满({@link #SEARCH_NODES})。真实世界有限但很大,LRTA* 的"保证到达"要配一个
 * 总预算才有意义 [Korf 1990];它按节点数计,所以"到不了"的结论与机器快慢无关(0006 按墙钟定预算时"没有路"时有时无)。
 */
final class Allowance {

    /**
     * 首段的时限。近的路几毫秒就搜到终点,用不满它;它管的是难路:靶场上 30 毫秒时一百二十八格以内的十九条里 11 条一次搜到,
     * 200 毫秒时 15 条,开始走的中位 17 毫秒不变、最慢的从 0.3 秒到 0.37 秒;再长(3 秒)17 条,最慢的要等五秒多。
     */
    static final long FIRST_NANOS = TimeUnit.MILLISECONDS.toNanos(200);
    /** 接续的时限封顶。 */
    static final long NEXT_MAX_NANOS = TimeUnit.SECONDS.toNanos(3);
    /** 接续要在身体走到半程终点之前搜完,留这么多余量(拷快照、排队、走得比几何最短还快一点)。 */
    static final long MARGIN_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
    /** 一刻的纳秒数:按原版 20 刻每秒折算路线剩下的刻数。刻速调过时折算有偏差,只影响接续想多久。 */
    static final long TICK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    /**
     * 一次搜索最多展开几个节点:只防内存。一个节点在平行数组、哈希表、堆与边里约一两百字节,精确判过的另带一步的事实(几百字节),
     * 二十万个节点几十兆;多个同伴同时搜也撑得住。
     */
    static final int SEARCH_NODES = 200_000;
    /**
     * 一趟的总预算:起点估价每一刻给这么多个节点。按每节点十微秒算,想的时间至多是按估价走完那段路的五分之一,
     * 再多就是在一片到不了的地方里打转。
     */
    static final int NODES_PER_TICK = 1_000;

    private final int fixed;
    private final SearchClock clock;
    private int total;
    private int spent;
    /** 接连几次搜索没交出路线:下一次的时限翻这么多倍。 */
    private int misses;

    /** @param fixed 调用方指定的总预算;0 是按估价定 */
    Allowance(int fixed, SearchClock clock) {
        if (fixed < 0) {
            throw new IllegalArgumentException("总预算不能为负:" + fixed);
        }
        this.fixed = fixed;
        this.total = fixed;
        this.clock = clock;
    }

    /** 这一趟从估价 {@code estimate}(刻)的地方起头:总预算按它定(调用方指定了的不动);已经定下的不再改。 */
    void begun(double estimate) {
        if (total == 0) {
            total = (int) Math.min(Integer.MAX_VALUE, Math.max(SEARCH_NODES, Math.ceil(estimate) * NODES_PER_TICK));
        }
    }

    /** 从身体脚下搜的时限:首段的,接连没搜出路线时翻倍,封顶 {@link #NEXT_MAX_NANOS}。 */
    Deadline first() {
        return new Deadline(clock, grown(FIRST_NANOS));
    }

    /**
     * 接续的时限:身体不停下来还能走 {@code aheadTicks} 刻({@link Continuation#aheadTicks}),在这之前搜完;不低于首段的(接连没搜出路线时
     * 同样翻倍),封顶 {@link #NEXT_MAX_NANOS}。
     */
    Deadline next(double aheadTicks) {
        long walking = (long) (aheadTicks * TICK_NANOS) - MARGIN_NANOS;
        return new Deadline(clock, Math.max(grown(FIRST_NANOS), Math.min(NEXT_MAX_NANOS, walking)));
    }

    private long grown(long nanos) {
        return Math.min(NEXT_MAX_NANOS, nanos << Math.min(misses, 20));
    }

    /** 一次搜索没交出路线:下一次多想一倍。 */
    void missed() {
        misses++;
    }

    /** 一次搜索交出了路线:时限回到原样。 */
    void found() {
        misses = 0;
    }

    /** 下一次搜索最多展开几个节点:一次的上限,和总预算还剩的,取小的。 */
    int nodes() {
        return total == 0 ? SEARCH_NODES : Math.max(1, Math.min(SEARCH_NODES, total - spent));
    }

    /** 一次搜索展开了 {@code nodes} 个节点;至少记一个,一次搜索再没有进展也要花预算。 */
    void spend(int nodes) {
        spent += Math.max(1, nodes);
    }

    /** 总预算用完了。 */
    boolean spent() {
        return total > 0 && spent >= total;
    }

    /** 已经花的节点数。 */
    int used() {
        return spent;
    }

    /** 目标换了:这一趟重新开始,总预算重新按新目标的估价定(调用方指定的仍是它)。 */
    void restart() {
        total = fixed;
        spent = 0;
        misses = 0;
    }
}
