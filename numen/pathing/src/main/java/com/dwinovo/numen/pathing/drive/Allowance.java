package com.dwinovo.numen.pathing.drive;

import java.util.concurrent.TimeUnit;

import com.dwinovo.numen.pathing.search.Deadline;
import com.dwinovo.numen.pathing.search.SearchClock;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.pathing.world.Kinematics;

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
 * <p><b>一段走到哪</b>:每次搜索到了目标、或到了一个估价比起点低 {@link #SEGMENT_BLOCKS} 格疾跑的节点就交出
 * ({@link com.dwinovo.numen.pathing.search.Search#advance}),身体走着,接续从那里往下搜。
 *
 * <p><b>放不放弃看节点</b>:一趟的收场只有一个判据——总预算(按展开的节点数)用完还没到;另外是搜索证明了到不了。总预算是"按估价走完
 * 要几段({@link #MIN_SEGMENTS} 段起)、每段展开典型的量({@link #SEGMENT_NODES})"的 {@link #SLACK} 倍。真实世界有限但很大,
 * LRTA* 的"保证到达"要配一个总预算才有意义 [Korf 1990];它按节点数计,所以"到不了"的结论与机器快慢无关(0006 按墙钟定预算时"没有路"时有时无)。
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
     * 一段推进多少:这一段的终点,估价比起点低这么多格疾跑的耗时。靶场(10-29)上扫过 32、64、96 格:到达一样(27 条可达的 26 条),
     * 64 与 96 的搜索总耗时比 32 少四成、路线价低一成,开始走的延迟三者相当;96 格时多数中距离一次搜到终点,难路上一段要搜得更久
     * (峡谷底一段十五万个节点),取 64。
     */
    static final int SEGMENT_BLOCKS = 64;
    /**
     * 一段典型展开几个节点。靶场(10-29,一段 64 格)上每次搜索展开数的九成分位约一万,难路(盆地、三百格长路)走完一段平均两千到
     * 四千个。
     */
    static final int SEGMENT_NODES = 15_000;
    /** 总预算是"按估价走完所有段、每段展开典型的量"的几倍:留给绕路、接续证出死路后的重搜、难走的那几段。 */
    static final int SLACK = 5;
    /**
     * 总预算至少按几段算:估价是下界,近处的估价不到一段,真实要走的路却可能远得多(峡谷底、石头里)。这个下限也定了"到不了"要想多久:
     * 估价不到一段的地方到不了,展开 {@code MIN_SEGMENTS × SEGMENT_NODES × SLACK} 个节点才收场。对比外挂上峡谷底那一趟估价不到三段、
     * 走完要展开二十万两千个节点,下限是它之上最小的:三段(22.5 万)两个规格都还到得了,两段(20 万)可挖可放的那一趟就到不了;
     * 22.5 万时到不了的短路要想两秒半到四秒(看机器忙不忙)。
     */
    static final int MIN_SEGMENTS = 3;

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

    /** 一段要推进多少估价(刻):{@link #SEGMENT_BLOCKS} 格疾跑的耗时。 */
    static double advance(BodyStats body) {
        return SEGMENT_BLOCKS * Kinematics.pace(body).sprint();
    }

    /**
     * 这一趟从估价 {@code estimate}(刻)的地方起头,一段推进 {@code advance} 刻:总预算按它定(调用方指定了的不动);已经定下的不再改。
     */
    void begun(double estimate, double advance) {
        if (total == 0) {
            double segments = Math.max(MIN_SEGMENTS, Math.ceil(estimate / advance));
            total = (int) Math.min(Integer.MAX_VALUE, segments * SEGMENT_NODES * SLACK);
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
