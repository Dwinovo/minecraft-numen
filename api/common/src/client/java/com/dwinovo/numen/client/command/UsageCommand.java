package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.provider.CacheWaste;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.TokenFormat;

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
        Usage sum = loop.usageTotals();
        if (sum.total() <= 0) {
            return "还没有用量记录——她一轮都还没开口。";
        }
        StringBuilder sb = new StringBuilder();
        long prompt = sum.promptTokens();
        sb.append("输入 ").append(group(prompt));
        if (sum.reportsCache()) {
            sb.append("\n  命中缓存 ").append(group(sum.cacheRead()))
                    .append(" (").append(TokenFormat.percent1(sum.cacheHitRate())).append("%)");
            sb.append("\n  新处理   ").append(group(sum.input() + sum.cacheWrite()));
            if (sum.cacheWrite() > 0) {
                sb.append(" (其中 ").append(group(sum.cacheWrite())).append(" 写入了缓存)");
            }
        }
        sb.append("\n输出 ").append(group(sum.output()));
        sb.append("\n合计 ").append(group(sum.total()));

        double last = loop.lastUsage().cacheHitRate();
        if (sum.reportsCache() && last >= 0) {
            sb.append("\n最近一轮命中率 ").append(TokenFormat.percent1(last)).append('%');
        }

        CacheWaste waste = loop.cacheWaste();
        if (waste.missedTokens() > 0) {
            // 这个数一涨就是前缀被动过:系统提示改了、工具清单变了、或者隔太久缓存过期
            sb.append("\n缓存重付 ").append(group(waste.missedTokens()))
                    .append(" tokens,").append(waste.missCount()).append(" 次");
        }
        return sb.toString();
    }

    /** 千分位——账本上的数要看得出量级,不缩写。 */
    private static String group(long n) {
        return String.format("%,d", n);
    }
}
