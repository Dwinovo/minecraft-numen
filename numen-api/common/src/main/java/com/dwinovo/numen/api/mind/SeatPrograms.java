package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.program.ProgramHost;
import com.dwinovo.numen.api.seat.ProgramResult;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.api.seat.SeatState;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 大脑把她的程序交给座位去跑({@link ProgramHost} 的这一头):一整段程序经 {@code program/run} 送到服务端的身体旁边,结局恰好交回一次;
 * 叫停、切断、叫停身体各是座位上的一个请求。结局里带着那一刻她的样子,交给 {@code states}。
 *
 * <p>线程:交回结局在 {@code main} 上(座位的答复在网络线程上到,这里切回大脑的线程)。
 */
final class SeatPrograms implements ProgramHost {

    private final SeatClient seat;
    private final UUID companion;
    private final Executor main;
    private final LongSupplier generation;
    private final Consumer<SeatState> states;
    /** 她此刻在答的那句话是谁说的:程序替他做事。 */
    private final java.util.function.Supplier<UUID> keeper;
    /** 等答复的程序;切断后晚到的结局换一个收件人({@link #afterwards}),不等了的摘掉。 */
    private final Map<String, Consumer<Program.Outcome>> waiting = new ConcurrentHashMap<>();

    SeatPrograms(SeatClient seat, UUID companion, Executor main, LongSupplier generation,
                 Consumer<SeatState> states, java.util.function.Supplier<UUID> keeper) {
        this.seat = seat;
        this.companion = companion;
        this.main = main;
        this.generation = generation;
        this.states = states;
        this.keeper = keeper;
    }

    @Override
    public void run(UUID who, String programId, String code, Consumer<Program.Outcome> done) {
        waiting.put(programId, done);
        seat.run(companion, generation.getAsLong(), programId, code, keeper.get())
                .whenComplete((result, failed) -> main.execute(() -> answered(programId, result, failed)));
    }

    private void answered(String programId, ProgramResult result, Throwable failed) {
        Consumer<Program.Outcome> to = waiting.remove(programId);
        Program.Outcome outcome;
        if (failed != null) {
            Throwable cause = failed instanceof CompletionException && failed.getCause() != null ? failed.getCause() : failed;
            Constants.LOG.warn("[numen-seat] program {} of {} did not run: {}", programId, companion, cause.toString());
            outcome = Program.Outcome.refused("the program did not run: " + cause.getMessage());
        } else {
            states.accept(result.state());
            outcome = result.outcome();
        }
        if (to != null) {   // 不等了的就没有收件人
            to.accept(outcome);
        }
    }

    @Override
    public void afterwards(String programId, Consumer<Program.Outcome> late) {
        waiting.computeIfPresent(programId, (id, was) -> late);
    }

    @Override
    public void forget(String programId) {
        waiting.remove(programId);
    }

    @Override
    public void interrupt(UUID who, String programId, String why) {
        seat.interrupt(companion, programId, why).whenComplete((done, failed) -> logged("interrupt", failed));
    }

    @Override
    public void cutOff(UUID who, String programId, boolean stopBody) {
        seat.cut(companion, programId, stopBody).whenComplete((done, failed) -> logged("cut", failed));
    }

    @Override
    public void stopBody(UUID who) {
        seat.stopBody(companion).whenComplete((state, failed) -> main.execute(() -> {
            if (failed == null) {
                states.accept(state);
            }
            logged("body stop", failed);
        }));
    }

    private void logged(String what, Throwable failed) {
        if (failed != null) {
            Constants.LOG.warn("[numen-seat] {} for {} was not delivered: {}", what, companion, failed.toString());
        }
    }
}
