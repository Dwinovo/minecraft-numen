package com.dwinovo.numen.program;

import com.dwinovo.numen.agent.script.Invocation;

import java.util.function.Consumer;

/**
 * 执行程序里的一次 API 调用。按函数在哪一端执行分成两个部件({@link ServerCalls} 进程内、{@link ClientCalls} 向主人客户端发反向请求),
 * 由 {@link RoutedCalls} 按函数的端分流;看着调用的 {@link ObservedCalls} 是套在外面的一层。
 */
@FunctionalInterface
public interface ProgramCalls {

    /**
     * @param done 结果({@code ApiReply} 写的那一份)恰好交回一次,任何线程、当场或之后都行
     * @param sent 调用交出去之后(回来之前)调一次,在服务端主线程上
     */
    void execute(String callId, Invocation invocation, Consumer<String> done, Runnable sent);
}
