package com.dwinovo.numen.api.agent.trace;

import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.store.AppendLog;
import com.dwinovo.numen.api.store.Io;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 一本旁路的轨迹:她每一轮看到了什么({@code turn},身体的样子就在发出去的那份运行期状态里)、寻路为什么放弃({@code path})。
 * 大脑那一头写它自己的每一轮,身体那一头({@code BodyTrace})写寻路的 {@code path} 一类,各写各的文件。
 *
 * <p><b>它绝不进会话历史。</b>{@code <runtime_state>} 现算、一个字都不入历史是有意为之——
 * 那些都会变,进了历史就是理直气壮的旧数。这里把每一轮现算的那一份写到旁边的文件里,读者是人和工具,不是模型。
 *
 * <p>一次服务端运行一个 session、一串文件 {@code trace/<session>-<段号>.jsonl}。{@code session} 是随机 long,
 * 不是启动时刻:时刻会撞,而且区分不了"哪一次运行"。段号从 1 起,写满 {@link #PER_SEGMENT} 条换下一段。
 *
 * <h2>为什么要分段</h2>
 * {@link AppendLog#open} 在首次追加时把整份文件读进内存。只按 session 分的话,一次长开服(连续几天)
 * 会把"开服要读多少"交给"这次开了多久";再切一刀之后,这个量被钉成一个常数,
 * 与开了多久、历史有多少都无关。读取侧多一步"列目录、按 ts 拼",数据一条不少。
 *
 * <p>线程:任何线程。写走宿主给的那条车道({@link Io})。
 */
public final class TraceRecorder {

    /** 记录格式的第 1 版,写在文件头里;读的一方(排查工具、测试)也用它打开。 */
    public static final AppendLog.Format FORMAT = AppendLog.Format.of(1);

    /**
     * 一段写多少条就换下一个文件。65536 条按一条 300 字节上下算是 20 MB,
     * 开一次文件最多读这么多。按条数而不是字节数:字节数要问磁盘,而这里在客户端主线程上。
     */
    static final int PER_SEGMENT = 65_536;

    /** 一次服务端运行里每只同伴的 session:重建大脑不换文件,重启才换。 */
    private static final Map<UUID, Long> SESSIONS = new ConcurrentHashMap<>();

    private final long session;
    private final int perSegment;
    /** 她的家(写{@code trace/}的那一层)。 */
    private final Path home;
    private final Io io;

    /** 正在写第几段,从 1 起。 */
    private int segment = 1;
    /** 这一段已经写了多少条。 */
    private int written;
    private AppendLog log;

    /** 上一轮对话的账;{@code null} = 这个 run 还没有上一轮。 */
    private Bill lastBill;
    /** 正在进行的这次模型调用是不是对话轮(压缩、目标评估不走这条,它们不动身体)。 */
    private boolean inTurn;
    /** 这一轮开始时的墙钟。 */
    private long turnStartedAt;
    /** 这一轮属于哪次 run。 */
    private long turnRun = -1;

    /** 一轮的账:耗时、token、实际派了几个工具。 */
    private static final class Bill {
        long run;
        long ms;
        long in;
        long out;
        int calls;
    }

    /**
     * @param home 她的家:轨迹写在 {@code home/trace/} 里
     * @param io   她那一条写车道
     */
    public TraceRecorder(Path home, Io io, UUID companion) {
        this(home, io, companion, PER_SEGMENT);
    }

    /**
     * @param perSegment 一段写多少条换下一个文件
     */
    TraceRecorder(Path home, Io io, UUID companion, int perSegment) {
        this.session = SESSIONS.computeIfAbsent(companion, id -> ThreadLocalRandom.current().nextLong());
        this.perSegment = perSegment;
        this.home = home;
        this.io = io;
        this.log = at(1);
    }

    public long session() {
        return session;
    }

    /** 正在写的这一段。 */
    public Path file() {
        return log.file();
    }

    /** 一次模型请求,{@code body} 是这一轮其余的字段({@code run}/{@code n} 由本类补)。 */
    public void turn(long runId, int n, JsonObject body) {
        if (lastBill != null && lastBill.run == runId && n > 1) {
            JsonObject bill = new JsonObject();
            bill.addProperty("ms", lastBill.ms);
            bill.addProperty("in", lastBill.in);
            bill.addProperty("out", lastBill.out);
            bill.addProperty("calls", lastBill.calls);
            body.add("lastTurn", bill);
        }
        body.addProperty("run", runId);
        body.addProperty("n", n);
        write("turn", body);
    }

    /**
     * 内核的事件里这边要接的:上一轮的耗时、token 与<b>实际派了几个工具</b>。
     *
     * <p>{@code calls} 是关键:输出很多 token 而 {@code calls} 只有 1,就是空转——推理烧在没有任何进展的地方。
     * 耗时与 token 都贴着真实发生的那一刻记,不事后推。压缩与目标评估也经 {@link LoopEvent.ModelUsed} 来,
     * 但它们不在这条链上,不算。
     */
    public void on(LoopEvent event) {
        switch (event) {
            case LoopEvent.TurnStarted started -> {
                inTurn = true;
                turnRun = started.runId();
                turnStartedAt = System.currentTimeMillis();
            }
            case LoopEvent.ModelUsed used -> {
                if (inTurn && used.purpose() == LoopEvent.Purpose.TURN) {
                    lastBill = new Bill();
                    lastBill.run = turnRun;
                    lastBill.ms = Math.max(0, System.currentTimeMillis() - turnStartedAt);
                    lastBill.in = used.usage().promptTokens();
                    lastBill.out = used.usage().output();
                }
            }
            case LoopEvent.AssistantMessage message -> {
                if (inTurn && lastBill != null && message.runId() == lastBill.run) {
                    lastBill.calls = message.turn().toolCalls().size();
                }
            }
            case LoopEvent.RunEnded ended -> {
                inTurn = false;
                if (lastBill != null && lastBill.run != ended.runId()) {
                    lastBill = null;
                }
            }
            default -> { }
        }
    }

    /** 通用口:{@code path} 走这里,调用点在寻路那一侧。 */
    public void event(String type, JsonObject body) {
        write(type, body);
    }

    private void write(String type, JsonObject body) {
        if (written >= perSegment) {
            segment++;
            written = 0;
            log = at(segment);
        }
        body.addProperty("session", session);
        log.append(type, body);
        written++;
    }

    private AppendLog at(int segment) {
        return new AppendLog(home.resolve("trace").resolve(session + "-" + segment + ".jsonl"), FORMAT, io);
    }
}
