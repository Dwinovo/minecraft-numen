package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.llm.ToolOutcome;
import com.dwinovo.numen.agent.provider.LlmToolCall;
import com.dwinovo.numen.agent.tool.TodoTool;

import java.util.List;

/**
 * 她用 {@link TodoTool} 写下的计划读成一份清单(Telegram 的清单消息:一条消息里几项待办,做完就勾)。计划就是那次调用的参数,
 * 读法只在 {@link TodoTool#plan}。对话流把它画成她说的一条消息;同一份计划只是状态变了,就在第一次出现的那条上原地更新,条目
 * 内容变了才另起一条——"是不是同一份"只看 {@link #sameItems}。
 *
 * <p>纯逻辑,不碰 Minecraft,单元测试直接喂调用与结果。
 */
public final class PlanChecklist {

    private PlanChecklist() {}

    /**
     * 这次工具调用写下的计划:是记计划的工具、收下了(结果不是失败)、参数读得出一份清单时是那份;否则是 null——那样的调用仍只是
     * 一次普通的调用。
     *
     * @param result 这次调用的结果;还没有是 null
     */
    public static List<TodoTool.Item> of(LlmToolCall call, String result) {
        if (!TodoTool.NAME.equals(call.name()) || result == null || ToolOutcome.failed(result)) {
            return null;
        }
        return TodoTool.plan(call.arguments());
    }

    /** 是不是同一份计划:条目一样多、逐条内容相同(状态不算)。 */
    public static boolean sameItems(List<TodoTool.Item> a, List<TodoTool.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).content().equals(b.get(i).content())) return false;
        }
        return true;
    }

    /** 做完了几项(抬头的"计划 2/5"里的 2)。 */
    public static int done(List<TodoTool.Item> items) {
        int n = 0;
        for (TodoTool.Item it : items) {
            if (it.status() == TodoTool.Status.COMPLETED) n++;
        }
        return n;
    }
}
