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
        // 空闲时进队列就当场清掉了,忙的时候才真排着——照实说哪一种。
        return loop.clearPending()
                ? "清空已排上,她手上这轮完就清"
                : "上下文清掉了。记录都留着,往上翻还能看";
    }
}
