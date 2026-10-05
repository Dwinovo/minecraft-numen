package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Invocation;
import com.dwinovo.numen.sdk.Dispatcher;

import java.util.function.Consumer;

/** 按函数在哪一端执行,把调用交给进程内的一份或向主人客户端发反向请求的一份:派发的分流只在这里。 */
final class RoutedCalls implements ProgramCalls {

    private final ProgramCalls server;
    private final ProgramCalls client;

    RoutedCalls(ProgramCalls server, ProgramCalls client) {
        this.server = server;
        this.client = client;
    }

    @Override
    public void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent) {
        (Dispatcher.runsOnServer(invocation) ? server : client).execute(callId, invocation, done, sent);
    }
}
