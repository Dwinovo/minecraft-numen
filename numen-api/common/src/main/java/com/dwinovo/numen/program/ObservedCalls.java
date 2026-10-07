package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Invocation;

import java.util.function.Consumer;

/** 套在执行口外面,把每次调用的派出、交出、回来报给 {@link CallObserver}。 */
final class ObservedCalls implements ProgramCalls {

    private final ProgramCalls inner;
    private final CallObserver observer;

    ObservedCalls(ProgramCalls inner, CallObserver observer) {
        this.inner = inner;
        this.observer = observer;
    }

    @Override
    public void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent) {
        observer.dispatched(callId, invocation);
        inner.execute(callId, invocation, reply -> {
            observer.replied(callId, reply);
            done.accept(reply);
        }, () -> {
            observer.sent(callId);
            sent.run();
        });
    }
}
