package com.dwinovo.numen.client.command;

import com.dwinovo.numen.client.agent.EntityAgentLoop;

/**
 * {@code /usage} —— 这个同伴的 token 账。
 *
 * <p>页脚那一行是随时能瞥一眼的摘要,这里是摊开的账本。两者读的是同一份数据
 * ({@link EntityAgentLoop#usageTotals()} 等),不各算各的。
 *
 * <p>输入按<b>缓存命中 / 未命中</b>拆——这是唯一与服务商无关的拆法。缓存写归到未命中
 * 那一侧:它是实打实处理过的量,只是顺便存了起来。
 */
final class UsageCommand implements ChatCommand {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "看 token 账";
    }

    @Override
    public String run(EntityAgentLoop loop, String args) {
        // 这份数据的重点是比例,读数卡一眼看得出;文本再列一遍就是第二份要维护的渲染。
        java.util.UUID id = loop.entityUuid();
        com.dwinovo.numen.client.screen.NumenScreen.openUsage(
                id, com.dwinovo.numen.client.agent.NumenRoster.instance().name(id));
        return null;
    }

}
