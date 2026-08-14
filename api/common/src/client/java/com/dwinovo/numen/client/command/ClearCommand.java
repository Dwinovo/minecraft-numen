package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

/**
 * {@code /clear} —— 清空上下文,她白纸一张地开始下一轮。
 *
 * <p>清的是<b>她看得见的上下文</b>,不是聊天记录:日志 append-only,记录全留档,
 * 界面往上翻照样都在,只是她不再带着走。想省 token 又想留个要点,用 {@code /compact}。
 */
final class ClearCommand implements ChatCommand {

    @Override
    public String name() {
        return "clear";
    }

    @Override
    public String description() {
        return "清空上下文重新开始(聊天记录保留)";
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    @Override
    public String unavailable(EntityAgentLoop loop) {
        return loop == null ? null : loop.clearProblem();
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        String refused = loop.requestClearContext();
        if (refused != null) {
            return refused;
        }
        // 成功不吭声:清完分隔线当场出现,排着时队列条目自己在聊天流里挂着——
        // 画面已经说明了一切,再回一行字是重复。
        return null;
    }
}
