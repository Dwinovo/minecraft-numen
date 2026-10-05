package com.dwinovo.numen.program;

import com.dwinovo.numen.Constants;
import com.dwinovo.numen.agent.inbox.EventQueue;
import com.dwinovo.numen.agent.script.Program;
import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.agent.script.SerialExecutor;
import com.dwinovo.numen.api.Internal;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.platform.ServerLifecycle;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * 在服务端跑她的 Lua 程序的<b>唯一入口</b>:主人客户端送来的整段程序({@code RunProgramPayload})、管理员的
 * {@code /numen drive <同伴> <程序>}、重启后接回她手上那件活(记下的那一行 Lua 再跑一遍)、GameTest 与单测都经它。
 *
 * <h2>谁做什么</h2>
 * <ul>
 *   <li>{@link Program}(agent):一段程序从头到回执,在它自己的执行体上;</li>
 *   <li>{@link ProgramCalls}:一次调用在哪一端执行——{@link ServerCalls} 排进主线程的车道({@link MainQueue}),
 *       {@link ClientCalls} 向主人客户端发反向请求({@link ClientTransport}),{@link RoutedCalls} 按函数的端分流;</li>
 *   <li>{@link RunModules}/{@link ModuleCache}:她的模块,客户端是真源,服务端只按内容缓存;</li>
 *   <li>这里:登记在跑的程序、准入(每只同伴一段,每位主人、全服各有上限)、把服务端发出的事件交给程序、停、清理。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 程序与它的虚拟机在自己的线程上,服务端主线程只在每刻开头执行车道里排着的调用({@link #tick}),<b>从不等程序</b>。
 * 一段程序的回执与所有 {@code done} 回调都在服务端主线程上。
 *
 * <h2>程序等的那件活的收尾</h2>
 * 程序派了一件占身体的活,这件活的收尾归这段程序:账写进回执,不再作为事件另送给主人的大脑(一件活的收尾只说一次)。
 * 判断在发事件的那一刻、服务端主线程上做({@link NumenEvents.Watcher}),所以"归谁"和"有没有送出去"不会各说各的;程序停下之后才到的
 * 收尾由程序转交({@link Program.Port#forward})。
 */
@Internal
public final class ServerPrograms {

    /**
     * 一段跑着的程序。
     *
     * @param claimed 程序刚派出的、占身体的那件活的编号(受理的回执一到就记下,程序线程还没来得及处理):它的收尾归这段程序
     */
    private static final class Running {
        final UUID companion;
        final UUID owner;
        final String programId;
        final MainQueue.Lane lane;
        /** 这段程序等着的反向请求:每刻查期限、程序结束时撤掉。 */
        final ClientCalls client;
        Program program;
        volatile String claimed;

        Running(UUID companion, UUID owner, String programId, MainQueue.Lane lane, ClientCalls client) {
            this.companion = companion;
            this.owner = owner;
            this.programId = programId;
            this.lane = lane;
            this.client = client;
        }
    }

    /**
     * 一次跑程序的请求。
     *
     * @param reportsToModel 回执是给模型看的(主人客户端送来的、GameTest 与单测里扮主人客户端的):程序等着的那件活的收尾就写进回执,
     *                       不再作为事件另送。管理员的 drive、重启后再跑的那一行没有模型读回执,它们派的活的收尾照常作为事件送到她手里
     */
    public record Request(String programId, String code, ModuleSet modules, boolean reportsToModel) {}

    private static final MainQueue QUEUE = new MainQueue();
    private static final ModuleCache CACHE = new ModuleCache(ProgramLimits.MODULE_CACHE_BYTES);
    /** 在跑的程序,按同伴:一只同伴同一时刻至多一段。 */
    private static final Map<UUID, Running> RUNNING = new ConcurrentHashMap<>();
    /** 每段程序的执行体各用虚拟线程:停在等结果上不占平台线程。 */
    private static final Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();
    /** 服务器刻数,{@link #tick} 每刻加一:反向请求的期限({@link ProgramLimits#CLIENT_ANSWER_TICKS})按它量。 */
    private static volatile long ticks;

    static {
        NumenEvents.watch(ServerPrograms::watch);
        ServerLifecycle.onStopped(ServerPrograms::dropAll);
    }

    private ServerPrograms() {}

    /**
     * 跑一段程序。结果({@link RunResult})经 {@code done} 恰好交回一次,在服务端主线程上:跑完的回执;缺模块正文时是
     * {@link RunResult.Missing},程序没跑,客户端带上再来;同伴已有一段在跑、主人或全服到了并发上限,当场是一张失败的回执。
     *
     * @param her       她的身体;没有世界的单测是 null
     * @param companion 她的 UUID
     * @param owner     主人的 UUID:模块缓存与并发上限按他算
     * @param transport 反向请求怎么到主人的客户端
     * @param observer  看着它的每次调用
     */
    public static void run(NumenPlayer her, UUID companion, UUID owner, Request request, ClientTransport transport,
                           CallObserver observer, Consumer<RunResult> done) {
        RunModules.Opened opened;
        try {
            opened = RunModules.open(owner, request.modules(), CACHE);
        } catch (IllegalArgumentException wrong) {
            done.accept(RunResult.refused("a module text sent with the program does not match its fingerprint: "
                    + wrong.getMessage()));
            return;
        }
        if (!opened.missing().isEmpty()) {
            done.accept(new RunResult.Missing(opened.missing()));
            return;
        }
        MainQueue.Lane lane = QUEUE.lane();
        ClientCalls client = new ClientCalls(her, companion, lane, transport, opened.modules(), () -> ticks);
        Running running = new Running(companion, owner, request.programId(), lane, client);
        String refused;
        synchronized (RUNNING) {
            refused = admit(her, companion, owner);
            if (refused == null) {
                RUNNING.put(companion, running);
            }
        }
        if (refused != null) {
            running.lane.close();
            done.accept(RunResult.refused(refused));
            return;
        }
        ProgramCalls calls = new ObservedCalls(new RoutedCalls(
                new ServerCalls(her, running.lane, job -> running.claimed = request.reportsToModel() ? job : null),
                client), observer);
        Constants.LOG.info("[numen-program] {} runs {} on the server: {}", companion, request.programId(),
                request.code());
        running.program = new Program(request.programId(), request.code(),
                new ProgramPort(her, opened.modules(), calls), new SerialExecutor(THREADS), outcome -> {
                    // 程序以任何方式结束(跑完、出错、打断、切断、主人断线)都经这里:它等着的反向请求在这一处撤掉
                    client.close();
                    // 回执先排进车道、再摘掉登记:从外面看"没有程序在跑"时,它的回执一定已经排着了
                    running.lane.post(() -> {
                        observer.ended(outcome);
                        done.accept(new RunResult.Ended(outcome));
                    });
                    running.lane.close();
                    RUNNING.remove(companion, running);
                });
        running.program.start();
    }

    /**
     * 服务端自己起的一段程序(管理员的 {@code /numen drive}、重启后再跑的那一行):没有主人的客户端送模块,用随模组发布的那一套;
     * 它调的客户端函数照样向她的主人的客户端发反向请求(主人不在线就是一条失败)。整张回执经 {@code receipt} 交回一次。
     *
     * @param tag 程序编号的前缀,日志里认它
     */
    public static void launch(NumenPlayer her, String tag, String code, CallObserver observer,
                              Consumer<String> receipt) {
        UUID owner = her.getOwnerUuid() != null ? her.getOwnerUuid() : her.getUUID();
        run(her, her.getUUID(), owner, new Request(tag + "-" + UUID.randomUUID(), code, ModuleSet.factory(), false),
                NetworkTransport.INSTANCE, observer, result -> receipt.accept(switch (result) {
                    case RunResult.Ended ended -> ended.outcome().receipt();
                    case RunResult.Missing missing -> throw new IllegalStateException(
                            "the built-in modules were sent whole, yet the server lacks " + missing.hashes());
                }));
    }

    /** 能不能再开一段:不能就是给模型的那句话。 */
    private static String admit(NumenPlayer her, UUID companion, UUID owner) {
        if (RUNNING.containsKey(companion)) {
            return "another program is already running on " + (her == null ? "this companion"
                    : her.getName().getString()) + " on the server; wait for it to end";
        }
        long mine = RUNNING.values().stream().filter(r -> r.owner.equals(owner)).count();
        if (mine >= ProgramLimits.PER_OWNER) {
            return mine + " programs of yours are running on the server, the most one owner may have at a time ("
                    + ProgramLimits.PER_OWNER + "); wait for one to end";
        }
        if (RUNNING.size() >= ProgramLimits.SERVER_WIDE) {
            return "the server is running as many programs as it allows (" + ProgramLimits.SERVER_WIDE
                    + "); try again in a moment";
        }
        return null;
    }

    /** 服务端发出了一条事件(发事件的那一刻、主线程上):归程序的收尾交给它,别的让它判断要不要停。 */
    private static boolean watch(UUID companion, EventQueue.Entry entry) {
        Running running = RUNNING.get(companion);
        if (running == null || running.program == null) {
            return false;
        }
        ScriptCall.Finish finish = NumenEvents.finishOf(entry);
        if (finish != null && finish.task().equals(running.claimed)) {
            running.claimed = null;
            running.program.taskFinished(entry);
            return true;
        }
        running.program.arrived(entry);
        return false;
    }

    /** 每个服务器刻开头调一次:在预算里执行程序排着的服务端调用,并让等主人客户端答复超了期限的反向请求失败。 */
    public static void tick() {
        ticks++;
        QUEUE.drain(ProgramLimits.TICK_NANOS_ALL, ProgramLimits.TICK_NANOS_PER_PROGRAM);
        RUNNING.values().forEach(r -> r.client.expire());
    }

    /**
     * 没有服务器刻的地方(单测、GameTest 的用例)推进:不看预算,把排着的调用都执行掉。
     *
     * @return 执行了几个调用
     */
    public static int pump() {
        return QUEUE.drainAll();
    }

    /**
     * 这具身体上的程序此刻是不是停在等外面的事(一次调用的结果、她派的活收尾)上:没有程序在跑,或者它没有任何活在做。
     * 没有服务器刻的地方(单测、GameTest 的用例)推进车道时据此知道什么时候不用再推了:先看这个、再 {@link #pump},
     * 闲着又没有调用可执行才算停稳。
     */
    public static boolean idle(UUID companion) {
        Running running = RUNNING.get(companion);
        return running == null || running.program == null || running.program.idle();
    }

    /** 这具身体上此刻有没有程序在跑。 */
    public static boolean running(UUID companion) {
        return RUNNING.containsKey(companion);
    }

    /** 让她正在跑的这段程序停在调用之间,{@code why} 是给模型的原因。不是这一段(已经结束了、换了一段)不理。 */
    public static void interrupt(UUID companion, String programId, String why) {
        Running running = RUNNING.get(companion);
        if (running != null && running.programId.equals(programId)) {
            running.program.interrupt(why);
        }
    }

    /** 这一轮被切断:这段程序当场停下。{@code stopBody} 见 {@link Program#cancel}。不是这一段不理。 */
    public static void cutOff(UUID companion, String programId, boolean stopBody) {
        Running running = RUNNING.get(companion);
        if (running != null && running.programId.equals(programId)) {
            running.program.cancel(stopBody);
        }
    }

    /** 这具身体离开世界:在跑的程序收掉(它等的活已由任务槽收尾)。 */
    public static void stop(NumenPlayer her) {
        Running running = RUNNING.get(her.getUUID());
        if (running != null) {
            running.program.cancel(false);
        }
    }

    /** 这位主人断线了:他名下的程序收掉,等他答复的反向请求都失败,缓存清掉,没收完的分片消息丢掉。 */
    public static void ownerLeft(UUID owner) {
        RUNNING.values().stream().filter(r -> r.owner.equals(owner)).forEach(r -> r.program.cancel(false));
        NetworkTransport.INSTANCE.ownerLeft(owner);
        CACHE.drop(owner);
        NumenNetwork.disconnected(owner);
    }

    /** 服务器停了:所有程序收掉,排着的调用作废。 */
    private static void dropAll() {
        RUNNING.values().forEach(r -> r.program.cancel(false));
        RUNNING.clear();
        QUEUE.clear();
        CACHE.clear();
        NetworkTransport.INSTANCE.clear();
    }
}
