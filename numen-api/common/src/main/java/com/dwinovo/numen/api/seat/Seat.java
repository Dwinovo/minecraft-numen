package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.event.NumenEvents;
import com.dwinovo.numen.mcp.rpc.RpcError;
import com.dwinovo.numen.mcp.rpc.RpcException;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * 一只同伴的座位:服务端这头记着的、连接来来去去之间不变的东西。发件箱(事件留到确认)、座位此刻坐在哪条连接上、经座位跑着的程序、
 * 最后一次告诉客户端的目录版本。它活到服务器关或她被遣散;客户端断线只是座位上没人({@link #detach}),事件继续攒在发件箱里,
 * 重连后补上。
 *
 * <h2>断线与程序</h2>
 * 经座位起的程序在连接断开时当场切断,程序的结局留着:等结局到了,作为一条 {@code program_stopped} 事件进发件箱
 * ({@link SeatEvent#outcome}),客户端重连时随续传补上,而不是丢掉。身体的去留照 body 0008,座位不管。
 *
 * <p>线程:状态由 {@code this} 守着;和世界打交道的部分在服务端主线程上({@link SeatWorld#main})。
 */
final class Seat {

    /** 发件箱最多留几条没确认的事件。 */
    static final int OUTBOX_CAP = 1000;

    /** 一段经座位起的程序,等它的结局。 */
    private static final class Run {
        final String program;
        final SeatConnection by;
        final CompletableFuture<ProgramResult> answer = new CompletableFuture<>();
        /** 起它的连接断了:结局不再答复给那条连接,留进发件箱。 */
        boolean detached;

        Run(String program, SeatConnection by) {
            this.program = program;
            this.by = by;
        }
    }

    private final Seats seats;
    final UUID companion;
    /** 她的创建者:只有他的连接坐得上。 */
    final UUID owner;
    private final Outbox outbox;
    private final Map<String, Run> running = new HashMap<>();
    private SeatConnection attached;
    /** 最后一次告诉客户端的目录版本。 */
    private String told = "";

    Seat(Seats seats, UUID companion, UUID owner) {
        this.seats = seats;
        this.companion = companion;
        this.owner = owner;
        this.outbox = new Outbox(OUTBOX_CAP, dropped -> Constants.LOG.warn("[numen-seat] {} did not acknowledge event {} "
                + "({}) in time and {} newer ones, so it is dropped from the outbox", companion, dropped.seq(),
                dropped.type(), OUTBOX_CAP));
    }

    // ---- 坐上与离开 ----

    /** 坐在这个座位上的连接;没人坐是 null。 */
    synchronized SeatConnection connection() {
        return attached;
    }

    /**
     * 连接坐上座位,把序号在 {@code lastSeq} 之后的事件按先后补给它。报来的序号比服务端发过的还大,是服务端重启之前的:作废,从头补。
     * 原来坐着别的连接的,它先下座(它起的程序切断)。
     */
    Opened attach(SeatConnection connection, long lastSeq) {
        SeatConnection previous = connection();
        if (previous != null && previous != connection) {
            previous.release(companion);
            detach(previous);
        }
        String version = version();
        synchronized (this) {
            attached = connection;
            told = version;
            boolean reset = lastSeq > outbox.last();
            for (SeatEvent event : outbox.after(reset ? 0 : lastSeq)) {
                connection.send(event);
            }
            return new Opened(seats.generation(companion), version, outbox.last(), outbox.first(), reset);
        }
    }

    /**
     * 这条连接离开座位(断线、被别的连接顶替):经它起、还在跑的程序当场切断,结局留着。不是坐着的那条连接不理。
     */
    void detach(SeatConnection connection) {
        List<Run> cut = new ArrayList<>();
        synchronized (this) {
            if (attached != connection) {
                return;
            }
            attached = null;
            for (Run run : running.values()) {
                if (run.by == connection && !run.detached) {
                    run.detached = true;
                    cut.add(run);
                }
            }
        }
        for (Run run : cut) {
            run.answer.cancel(true);   // 等答复的那个处理线程不必再等:答复没有去处了
            seats.world().main().execute(() -> seats.world().programs().cutOff(companion, run.program, false));
        }
    }

    // ---- 事件 ----

    /** 服务端发出了一条事件(主线程):盖上序号、代数与她此刻的样子,进发件箱,坐着的连接马上收到。 */
    void publish(EventQueue.Entry entry) {
        SeatWorld world = seats.world();
        add(SeatEvent.of(companion, seats.generation(companion), entry, world.state(companion), world.gameTime()));
    }

    private synchronized void add(SeatEvent event) {
        SeatEvent stamped = outbox.add(event);
        if (attached != null) {
            attached.send(stamped);
        }
    }

    /** 客户端收到了 {@code seq} 号及以前的。 */
    void ack(long seq) throws RpcException {
        if (seq > outbox.last()) {
            throw new RpcException(RpcError.INVALID_PARAMS, "params.seq is " + seq + ", but the last event sent was "
                    + outbox.last());
        }
        outbox.ack(seq);
    }

    // ---- 目录 ----

    /** 此刻的目录,并记下告诉过客户端的版本。 */
    JsonObject catalog() {
        JsonObject catalog = seats.world().catalog(companion);
        synchronized (this) {
            told = catalog.get("version").getAsString();
        }
        return catalog;
    }

    private String version() {
        return seats.world().catalog(companion).get("version").getAsString();
    }

    /** 注册表变了:目录版本和告诉过客户端的不同,就通知坐着的连接再取一次。 */
    void catalogMayHaveChanged() {
        String version = version();
        SeatConnection to;
        synchronized (this) {
            if (attached == null || version.equals(told)) {
                return;
            }
            told = version;
            to = attached;
        }
        to.catalogChanged(companion, version);
    }

    // ---- 程序 ----

    /**
     * 经座位跑一段程序,等到结局。代数在主线程上核对(身体进世界也在那里记账),对不上不起程序。
     *
     * @throws RpcException 这条连接没有坐着座位、程序编号已在跑、代数对不上
     */
    ProgramResult run(SeatConnection by, String program, long believed, String code) throws Exception {
        Run run = new Run(program, by);
        synchronized (this) {
            if (attached != by) {
                throw SeatErrors.noSeat(companion);
            }
            if (running.putIfAbsent(program, run) != null) {
                throw new RpcException(RpcError.INVALID_PARAMS, "a program named " + program
                        + " is already running on this seat");
            }
        }
        seats.world().main().execute(() -> start(run, believed, code));
        try {
            return run.answer.get();
        } catch (ExecutionException failed) {
            if (failed.getCause() instanceof Exception e) {
                throw e;
            }
            throw failed;
        }
    }

    private void start(Run run, long believed, String code) {
        long actual = seats.generation(companion);
        synchronized (this) {
            if (run.detached) {
                running.remove(run.program, run);
                return;
            }
            if (believed != actual) {
                running.remove(run.program, run);
                run.answer.completeExceptionally(SeatErrors.staleGeneration(believed, actual));
                return;
            }
        }
        seats.world().programs().run(companion, run.program, code, outcome -> finished(run, outcome));
    }

    private void finished(Run run, Program.Outcome outcome) {
        SeatWorld world = seats.world();
        String state = world.state(companion);
        long game = world.gameTime();
        long generation = seats.generation(companion);
        synchronized (this) {
            running.remove(run.program, run);
            if (!run.detached) {
                run.answer.complete(new ProgramResult(run.program, outcome, generation, state, game));
                return;
            }
            EventQueue.Entry entry = NumenEvents.programStopped(world.dayTime(), run.program, outcome.message(),
                    System.currentTimeMillis());
            add(SeatEvent.ofOutcome(companion, generation, entry, outcome, state, game));
        }
    }

    /** 叫程序停在调用之间。 */
    void interrupt(String program, String why) {
        seats.world().main().execute(() -> seats.world().programs().interrupt(companion, program, why));
    }

    /** 当场切断程序。 */
    void cut(String program, boolean stopBody) {
        seats.world().main().execute(() -> seats.world().programs().cutOff(companion, program, stopBody));
    }

    /** 叫停身体上在跑的活。 */
    void stopBody() {
        seats.world().main().execute(() -> seats.world().programs().stopBody(companion));
    }
}
