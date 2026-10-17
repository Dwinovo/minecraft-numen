package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 外接大脑伸向同伴的手:{@link McpServer} 对她们的全部接触都收在这一个口里。宿主不关心同伴的大脑怎么接——
 * 实现 {@link ServerActuator} 直接操作进程内的 {@code CompanionLoop}。
 *
 * <p>每个方法可在任意线程调,工作由实现切到自己的主线程,结果经 future 回来。每个调用都寻址到一只同伴的 UUID,
 * 各同伴独立执行,所以一个外脑可以并行驱动好几只。
 */
public interface ExternalHands {

    /** 主人的一只同伴:稳定 id + 显示名。 */
    record Companion(UUID uuid, String name) {}

    /** 主人名下此刻在世界里的同伴。 */
    CompletableFuture<List<Companion>> companions();

    /**
     * 按名字召唤一只新同伴(与面板的"+"同一条路,同名幂等)。身体稍后才到,调用方用 {@link #companions} 确认。
     *
     * @return 请求发出去了为 true;当前不在世界里为 false
     */
    CompletableFuture<Boolean> create(String name);

    /** 永久遣散一只同伴(与面板的"✕"同一条路)。名册稍后才更新。 */
    CompletableFuture<Boolean> delete(UUID companion);

    /**
     * 取走此刻队里所有给模型看的事件文本(捎带的也交)。长轮询的到点清扫与程序回执的捎带都走这里,事件不会交两遍。
     *
     * @return 取走的事件拼段;没有是 null
     */
    CompletableFuture<String> takeEvents(UUID companion);

    /** 停靠到她有会叫醒她的条目为止,撤销与取走的批次怎么交代见 {@link ExternalWait.Parked}。 */
    ExternalWait.Parked awaitEvents(UUID companion);

    /** 替她说话:气泡、聊天行、她绑定的声线,与内置大脑的回复同一套表现。 */
    CompletableFuture<Boolean> say(UUID companion, String text);

    /**
     * 用她的身体跑一个工具(与内置大脑用的是同一批工具,不经模型)。结果是 {@code ToolOutcome} 的 JSON 串;
     * 失败(未知工具、参数错、工具抛错)也以失败结果回来,不是异常的 future。
     *
     * @param argsJson 工具参数的 JSON 对象串;空视作 {@code {}}
     */
    CompletableFuture<String> invoke(UUID companion, String toolName, String argsJson);
}
