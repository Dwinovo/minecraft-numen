package com.dwinovo.numen.client.screen.chat;

import com.dwinovo.numen.agent.script.ScriptCall;
import com.dwinovo.numen.cli.TodoCommands;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.List;

/**
 * 她在一次脚本运行里写下的计划({@code numen.todo.write})读成一份清单(Telegram 的清单消息:一条消息里几项待办,做完就勾)。
 * 那次调用的参数原样留在脚本回执里({@link ScriptCall#ECHOED}),一项的写法只在 {@link TodoCommands.Item#parse}。
 * 对话流把它画成她说的一条消息;同一份计划只是状态变了,就在第一次出现的那条上原地更新,条目内容变了才另起一条——
 * "是不是同一份"只看 {@link #sameItems}。
 *
 * <p>纯逻辑,不碰 Minecraft,单元测试直接喂回执。
 */
public final class PlanChecklist {

    private PlanChecklist() {}

    /**
     * 一次脚本运行的回执里写下的最后一份计划;没写计划、回执不是 JSON、留下的参数读不出一份清单时是 null——那样的运行仍只是
     * 一次普通的调用。
     */
    public static List<TodoCommands.Item> of(String receipt) {
        if (receipt == null) {
            return null;
        }
        JsonElement root;
        try {
            root = JsonParser.parseString(receipt);
        } catch (RuntimeException e) {
            return null;   // 回执不是 JSON:没有清单可画
        }
        if (!root.isJsonObject() || !(root.getAsJsonObject().get("data") instanceof JsonObject data)
                || !(data.get(ScriptCall.ECHOED) instanceof JsonArray echoed)) {
            return null;
        }
        for (int i = echoed.size() - 1; i >= 0; i--) {
            if (echoed.get(i) instanceof JsonObject echo) {
                List<TodoCommands.Item> plan = TodoCommands.plan(echo);
                if (plan != null) {
                    return plan;
                }
            }
        }
        return null;
    }

    /** 是不是同一份计划:条目一样多、逐条内容相同(状态不算)。 */
    public static boolean sameItems(List<TodoCommands.Item> a, List<TodoCommands.Item> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).content().equals(b.get(i).content())) return false;
        }
        return true;
    }

    /** 做完了几项(抬头的"计划 2/5"里的 2)。 */
    public static int done(List<TodoCommands.Item> items) {
        int n = 0;
        for (TodoCommands.Item it : items) {
            if (it.status() == TodoCommands.Status.COMPLETED) n++;
        }
        return n;
    }
}
