package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

/**
 * {@code /compact} —— 整理记忆。
 *
 * <p>可用性问 {@link EntityAgentLoop#compactProblem()},那是唯一的判据。补全行会把
 * 理由原样显示出来——从前那颗按钮只会灰着,不说为什么。
 */
final class CompactCommand implements ChatCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "整理记忆";
    }

    @Override
    public boolean touchesContext() {
        return true;
    }

    @Override
    public String unavailable(EntityAgentLoop loop) {
        return loop == null ? null : loop.compactProblem();
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        String refused = loop.requestCompact();
        if (refused != null) {
            return refused;
        }
        // 空闲时进队列就当场走掉了,忙的时候才真排着——照实说哪一种。
        return loop.isCompacting() ? "开始整理记忆…" : "整理记忆已排上,她手上这轮完就走";
    }
}
