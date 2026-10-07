package com.dwinovo.numen.task;

import java.util.function.Consumer;

/**
 * 一具身体受理之前在准备的那一次调用,最多一件。身体只有一个槽,准备也只有一件:后派的顶替先派的(先派的回一条没开始的
 * 结果),否则两件同时准备、谁的结论先回来谁先受理,后派的反而会被先派的顶掉。准备期间她手上的活照做,新活受理的那一刻才
 * 顶掉它({@link TaskDispatch})。
 *
 * <p>不碰身体:结论出来之后怎么办(受理进槽、回错误结果)由派它的一方交进来({@link Call#conclude})。
 */
final class Preparing {

    /**
     * 一次在准备的调用。
     *
     * @param preparation 它的准备
     * @param conclude    结论出来之后:就绪就受理,不成就回错误结果;恰好调一次
     */
    record Call(Preparation preparation, Consumer<Preparation.Readiness> conclude) {}

    private Call call;

    /** 开始准备 {@code next}:之前还在准备的那一件被它顶替。当场就有结论的当场了结。 */
    void begin(Call next) {
        withdraw("a newer body action came in before it was ready");
        call = next;
        tick();
    }

    /** 每刻问一次在准备的那一件:有结论就了结它。 */
    void tick() {
        if (call == null) {
            return;
        }
        Preparation.Readiness readiness = call.preparation().poll();
        if (readiness == null) {
            return;
        }
        Call done = call;
        call = null;
        done.conclude().accept(readiness);
    }

    /** 在准备的那一件不做了:在飞的搜索作废,调用回一条没开始的结果,说为什么。没有在准备的什么都不做。 */
    void withdraw(String why) {
        if (call == null) {
            return;
        }
        Call dropped = call;
        call = null;
        dropped.preparation().cancel();
        dropped.conclude().accept(Preparation.Readiness.refused(TaskResult.cancelled("not started: " + why)));
    }

    /** 在准备的那一件作废、不回结果:那次调用已经由别处了结(她死了,客户端按死因结算了在飞的调用)。 */
    void drop() {
        if (call != null) {
            call.preparation().cancel();
            call = null;
        }
    }
}
