package com.dwinovo.numen.api.mcp.server;

import com.dwinovo.numen.api.agent.inbox.ExternalWait;
import com.dwinovo.numen.api.agent.llm.ToolOutcome;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 交给外接大脑的正文,与传输无关:输入是同伴和这次调用,输出是几段文本。MCP 端点(不论手写还是将来换成官方 SDK、
 * 不论哪一代线路)只调这里,再把文本装进自己的结果信封。
 *
 * <p>外接大脑看到的与内置大脑看到的是同一份、同一处生成:事件是 {@code EventQueue.render} 渲染的那一份(取件的口也是
 * 内脑用的那个),程序的结果是 {@link ToolOutcome#modelText}(内脑读的那句,不含结构化数据),她此刻的
 * {@code <runtime_state>} 是内置大脑每次请求末尾挂的那一份({@code RuntimeState#xml},同一份身体快照),它不入会话历史,
 * 所以每次都现取、挂在正文最后。
 */
final class ExternalBrain {

    /** 查名册、取件、读状态这类很快的事等多久;只有动作类工具用配置里的限时。 */
    static final int CONTROL_TIMEOUT_SECONDS = 10;
    /**
     * get_events 长等待最长多久:外脑每调一次工具就是一次完整的模型请求,所以没事就一直等,有事(会叫醒她的条目,判据见
     * {@link ExternalWait})立刻返回。给桥接层(mcp-remote 等)和客户端的请求超时留余量(Codex 的工具超时默认 60 秒)。
     */
    static final int MAX_WAIT_SECONDS = 50;

    private static final String NO_NEWS = "(no new events — call get_events again to keep waiting; the owner's words "
            + "and world happenings land here)";

    /** 一次调用要交的正文:按顺序的几段文本,和这次调用算不算失败。 */
    record Body(List<String> texts, boolean failed) {}

    private ExternalBrain() {}

    /**
     * 一个程序(或别的她的工具)跑完之后的正文:结果的话在前;这段时间到达的事件其次(取走即不再由 get_events 交,外脑干活时
     * 不必另外轮询);她此刻的运行期状态在最后。
     *
     * @param result {@code ToolOutcome} 的 JSON 串
     */
    static Body afterProgram(ExternalHands hands, UUID companion, String result) throws Exception {
        String events = hands.takeEvents(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return body(ToolOutcome.modelText(result), ToolOutcome.failed(result), events, hands, companion);
    }

    /**
     * 等她的消息:停靠在收件箱上,会叫醒她的条目一到立即整批返回;到点清扫,有什么给什么(捎带的也交),没有就如实说没有。
     * 叫醒只是提前,正确性由到点清扫兜底。正文最后同样是她的运行期状态。
     *
     * @param waitSeconds 最长等多久,0 = 立刻取走队里现有的
     */
    static Body events(ExternalHands hands, UUID companion, int waitSeconds) throws Exception {
        String batch = null;
        if (waitSeconds > 0) {
            ExternalWait.Parked parked = hands.awaitEvents(companion);
            try {
                batch = parked.batch().get(waitSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException deadline) {
                // 到点,转清扫——叫醒只是提前,正确性由清扫兜底
            } finally {
                // 撤销与叫醒谁先谁得:到点那一刻叫醒恰好取走的批次由撤销交回,取走的一定交到外脑手里
                String late = parked.cancel().get();
                if (batch == null) batch = late;
            }
        }
        if (batch == null) {
            String rest = hands.takeEvents(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            batch = rest != null ? rest : NO_NEWS;
        }
        return body(batch, false, null, hands, companion);
    }

    private static Body body(String text, boolean failed, String events, ExternalHands hands, UUID companion)
            throws Exception {
        List<String> texts = new ArrayList<>();
        texts.add(text);
        if (events != null) {
            texts.add(events);
        }
        String state = hands.runtimeState(companion).get(CONTROL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (state != null && !state.isBlank()) {
            texts.add(state);
        }
        return new Body(texts, failed);
    }
}
