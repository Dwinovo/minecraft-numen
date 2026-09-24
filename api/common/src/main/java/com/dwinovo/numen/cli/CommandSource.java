package com.dwinovo.numen.cli;

/**
 * 一次命令调用的来源:在哪一侧解析、为哪只同伴、结果往哪送。它是命令调度器的 Brigadier 源对象。
 *
 * <p>只有两种,各带那一侧执行时要的东西:服务端是 {@link ServerSource}(活体、这次调用本身、回信口),
 * 主人客户端是 {@link ClientSource}(她的 UUID、回信口)。动作在哪一侧执行由登记时选的处理函数决定
 * ({@link CommandGroup#server} / {@link CommandGroup#client}),源对象只说"现在在哪一侧"。
 */
public sealed interface CommandSource permits ServerSource, ClientSource {

    /** 送回这次调用的结果(一个 {@code TaskResult} 的 JSON),恰好一次。 */
    void reply(String resultJson);
}
