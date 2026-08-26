package com.dwinovo.numen.client.command;

import com.dwinovo.numen.agent.provider.CacheWaste;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.TokenFormat;
import com.dwinovo.numen.client.ui.widget.SelectPanel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /usage} —— 这个同伴的 token 账。
 *
 * <p>与 {@code /skills} 同款:贴着输入框弹出来,不接管整块面板——看完账多半还要接着
 * 说话,把人从输入框里赶出去是多余的一步。
 *
 * <p>输入按<b>缓存命中 / 新处理</b>拆,这是唯一与服务商无关的拆法;缓存写归到新处理
 * 那一侧,它是实打实处理过的量,只是顺便存了起来。页脚那一行读的是同一份数据
 * ({@link EntityAgentLoop#usageTotals()} 等),不各算各的。
 */
final class UsageCommand implements PageCommand {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return "看 token 账";
    }

    @Override
    public SelectPanel.Page page(EntityAgentLoop loop) {
        return new UsagePage(loop);
    }

    /** 每次 {@link SelectPanel.Page#rows} 都现取:账在她说话时随时在变。 */
    private record UsagePage(EntityAgentLoop loop) implements SelectPanel.Page {

        @Override
        public String title() {
            return "Token 账   Esc 返回";
        }

        @Override
        public List<SelectPanel.Row> rows() {
            Usage u = loop.usageTotals();
            List<SelectPanel.Row> rows = new ArrayList<>();
            if (u.total() <= 0) {
                rows.add(new SelectPanel.Row("还没有用量记录", "她一轮都还没开口", null));
                return rows;
            }
            rows.add(new SelectPanel.Row("输入", group(u.promptTokens()), null));
            if (u.reportsCache()) {
                // 圆点当健康灯:命中率高是绿的,低了变红——一眼看出缓存有没有在干活
                rows.add(new SelectPanel.Row("  命中缓存",
                        group(u.cacheRead()) + "  " + TokenFormat.percent1(u.cacheHitRate()) + "%",
                        u.cacheHitRate() >= 0.7));
                rows.add(new SelectPanel.Row("  新处理",
                        group(u.input() + u.cacheWrite()), null));
                if (u.cacheWrite() > 0) {
                    rows.add(new SelectPanel.Row("  其中写入缓存", group(u.cacheWrite()), null));
                }
            }
            rows.add(new SelectPanel.Row("输出", group(u.output()), null));
            rows.add(new SelectPanel.Row("合计", group(u.total()), null));

            double last = loop.lastUsage().cacheHitRate();
            if (u.reportsCache() && last >= 0) {
                rows.add(new SelectPanel.Row("最近一轮命中率",
                        TokenFormat.percent1(last) + "%", last >= 0.7));
            }
            CacheWaste waste = loop.cacheWaste();
            if (waste.missedTokens() > 0) {
                // 这一行出现就是前缀被动过:提示词改了、工具清单变了、或缓存过期
                rows.add(new SelectPanel.Row("缓存重付",
                        group(waste.missedTokens()) + " · " + waste.missCount() + " 次", false));
            }
            return rows;
        }

        /** 账没有可按的东西——每一行都是读数。 */
        @Override
        public boolean activate(int index) {
            return false;
        }

        /** 千分位:账要看得出量级,不缩写。 */
        private static String group(long n) {
            return String.format(Locale.ROOT, "%,d", n);
        }
    }
}
