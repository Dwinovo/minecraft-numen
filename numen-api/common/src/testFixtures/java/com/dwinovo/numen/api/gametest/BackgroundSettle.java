package com.dwinovo.numen.api.gametest;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ForkJoinPool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.Util;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 测试服务器每一刻开头先等后台工作告一段落再往下走。
 *
 * <p>结果本身是确定的(寻路的预算是节点数,程序的每一步由服务端的调用决定),什么时候到却取决于后台线程:寻路在线程池里算,
 * 程序在自己的虚拟线程上跑,而 GameTest 的时限按刻数算、测试服务器又尽可能快地推进刻,机器一忙时限就先到了。每刻开头等
 * 到这些都停下,一件活要几刻就是几刻,和机器快慢无关。
 *
 * <p>等两处:每刻开头({@link CompanionTickDispatcher#beforeTick},两个模组加载器的测试服务器都走那一处),和每刻收尾、服务端执行别的线程
 * 排给它的任务之前({@link #afterTick},经 {@code GameTestServerTickMixin})。这一刻里起的
 * 后台活,做完之后排给主线程的回调(签好的皮肤、MCP 请求要主线程做的那一步)就总在这一刻的收尾执行,不看后台线程赶没赶上。
 * 主线程自己在一刻当中去取结论的(寻路的搜索),取的那一下等它做完({@link #await})。
 *
 * <p>等的时候主线程照常执行排给它的任务:后台那一头可能正等着主线程替它做一步(MCP 的请求列工具、读背包),不执行就互相等死。
 *
 * <p>除了套件登记的来源,原版的区块系统也是一个来源({@code chunksUnsettled}),每次都等。区块升格(加载好、方块刻、实体刻)走一串
 * 跨线程的交接:玩家票据先经 {@code DistanceManager} 的节流排序器(在 {@code Util.backgroundExecutor()} 上跑)再排回区块的主线程
 * 执行器才加上,加上之后要等区块主线程执行器的 {@code runDistanceManagerUpdates} 才排出升格;{@code ChunkMap.prepareAccessibleChunk}/
 * {@code prepareTickingChunk} 的一步经 {@code ChunkMap} 的排序器转一道工作线程,{@code prepareEntityTickingChunk} 与确认升格的
 * {@code onFullChunkStatusChange} 排在区块的主线程执行器上;生成与光照在工作线程,读档在存储 I/O 线程。区块的主线程执行器只在
 * {@code MinecraftServer.pollTaskInternal} 里、且这一刻还有剩余墙钟时间({@code haveTime})时才执行——{@code managedBlock} 也一样,
 * 它只让服务端自己的任务不看时间——而测试服两刻之间的 {@code GameTestServer.waitUntilNextTick} 只是 {@code runAllTasks},不睡:
 * 一个区块哪一刻进实体刻({@code ServerLevel.isPositionEntityTicking}),要看工作线程赶没赶上、这一刻剩没剩时间,机器一忙就晚一两刻。
 * 所以每次等的时候都亲手把各维的区块主线程执行器做空({@code runChunkTasks}),再等这些线程都停下,反复到全都落定,哪一刻升格就只看刻数。
 *
 * <p>等待有上限({@link #LIMIT_SECONDS}):到了还有没停下的,说明那一头卡住了,不是慢,抛出并点名是谁,服务器带着这句话停。
 */
public final class BackgroundSettle {

    private static final Logger LOG = LogUtils.getLogger();
    /** 等多久算卡住:远大于任何正常的一刻。 */
    static final long LIMIT_SECONDS = 60;

    private BackgroundSettle() {}

    /** 登记了的后台来源:每一项答"此刻还没停下的是什么",停下了是 {@code null}。 */
    private static final List<Supplier<String>> SOURCES = new CopyOnWriteArrayList<>();

    /** @param sources 每一项答"此刻还没停下的是什么",停下了是 {@code null} */
    static void install(List<Supplier<String>> sources) {
        SOURCES.addAll(sources);
        CompanionTickDispatcher.beforeTick(BackgroundSettle::settle);
    }

    /** 每刻收尾、服务端执行排给它的任务之前调:等这一刻里起的后台活做完。 */
    public static void afterTick(MinecraftServer server) {
        settle(server);
    }

    /**
     * 主线程在一刻当中问到一件还没做完的后台活:等它做完再往下,上限同样是 {@link #LIMIT_SECONDS}。每刻开头与收尾的等待管刻与刻之间,
     * 这件活要是在这一刻里起的、又在这一刻里被问到,问到时做没做完本来看后台线程赶没赶上;等它做完,结论就总落在问的这一刻。
     *
     * <p>不执行排给主线程的任务:这样等的活(寻路的搜索)不靠主线程,而在一刻当中执行它们,会把它们挪到一个看线程快慢的位置上。
     * 活自己抛出的错不在这里报,问的一方取结论时原样拿到。
     */
    public static void await(Future<?> work) {
        try {
            work.get(LIMIT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | CancellationException e) {
            // 做完了,只是失败或被叫停:结论交给问的一方去取
        } catch (TimeoutException e) {
            String why = "background work asked for on the main thread did not finish within " + LIMIT_SECONDS + " s: " + work;
            LOG.error("[numen-gametest] {}", why);
            throw new IllegalStateException(why);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while the main thread waited for background work", e);
        }
    }

    /**
     * 等的时候用原版"主线程等一件事"的写法({@code managedBlock}):排给主线程的任务不论这一刻还剩不剩时间都执行。直接调
     * {@code pollTask} 不行——原版只在这一刻还没超时、或任务已经排了三刻以上时才执行它,这一刻一超时,后台等主线程、主线程等后台,
     * 直到后台那一头自己的时限到了才散。
     */
    private static void settle(MinecraftServer server) {
        runChunkTasks(server);
        if (firstUnsettled(server) == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(LIMIT_SECONDS);
        // 停下来的判断与报错用同一次读数:读完之后后台又动了是下一次等的事,不是卡住
        String[] unsettled = new String[1];
        server.managedBlock(() -> {
            runChunkTasks(server);
            return (unsettled[0] = firstUnsettled(server)) == null || System.nanoTime() > deadline;
        });
        if (unsettled[0] != null) {
            String why = "background work did not settle within " + LIMIT_SECONDS + " s: " + unsettled[0];
            LOG.error("[numen-gametest] {}", why);
            throw new IllegalStateException(why);
        }
    }

    private static String firstUnsettled(MinecraftServer server) {
        for (Supplier<String> source : SOURCES) {
            String unsettled = source.get();
            if (unsettled != null) {
                return unsettled;
            }
        }
        return chunksUnsettled(server);
    }

    /**
     * 把各维的区块主线程执行器做空:排着的任务,和它每次取任务前先跑的票据与区块等级更新({@code runDistanceManagerUpdates})。
     * 原版只在这一刻还有剩余墙钟时间时才做这些,这里不看时间。
     */
    private static void runChunkTasks(MinecraftServer server) {
        for (ServerLevel level : server.getAllLevels()) {
            while (level.getChunkSource().pollTask()) {
                // 一次一件,做到没有为止
            }
        }
    }

    /**
     * 原版的区块系统还有没落定的活:存储 I/O 线程、工作线程({@code Util.backgroundExecutor()}:生成、光照、两个排序器)在忙,
     * 或者哪一维的区块主线程执行器里还排着任务。
     *
     * <p>判断在主线程上做,判的时候主线程自己不产生新活;各处按交接的方向查(存储 I/O 交给工作线程与主线程,工作线程交给主线程),
     * 交出去的那一方在交完之前算忙,所以先查上游、后查下游,一件活不会在两次读数之间从没查的地方溜到查过的地方。排给区块主线程执行器的
     * 任务由 {@code runChunkTasks} 做完。
     *
     * <p>两个池按这一版原版的具体类型取:存储 I/O 是 {@code Executors.newCachedThreadPool} 出的 {@link ThreadPoolExecutor},工作线程是
     * {@link ForkJoinPool}。原版换了池的类型就在第一刻的强转上抛出,不会悄悄当成已经落定。
     */
    private static String chunksUnsettled(MinecraftServer server) {
        ThreadPoolExecutor io = (ThreadPoolExecutor) Util.ioPool();
        if (io.getActiveCount() > 0 || !io.getQueue().isEmpty()) {
            return "chunk storage I/O: " + io;
        }
        ForkJoinPool workers = (ForkJoinPool) Util.backgroundExecutor();
        if (!workers.isQuiescent()) {
            return "chunk workers (generation, lighting, task sorting): " + workers;
        }
        for (ServerLevel level : server.getAllLevels()) {
            ServerChunkCache chunks = level.getChunkSource();
            if (chunks.getPendingTasksCount() > 0) {
                return chunks.getPendingTasksCount() + " chunk tasks queued for the main thread in " + level.dimension().location();
            }
        }
        return null;
    }
}
