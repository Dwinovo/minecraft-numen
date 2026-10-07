package com.dwinovo.numen.client.command;

import net.minecraft.client.resources.language.I18n;
import com.dwinovo.numen.data.ModLanguageData.Keys;
import com.dwinovo.numen.agent.provider.CacheWaste;
import com.dwinovo.numen.agent.provider.Usage;
import com.dwinovo.numen.client.agent.EntityAgentLoop;
import com.dwinovo.numen.client.ui.TokenFormat;
import com.dwinovo.numen.client.ui.widget.Popup;
import com.dwinovo.numen.client.ui.widget.Readout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /usage} —— 这个同伴的 token 账,贴着输入框弹一张读数卡。
 *
 * <p>不接管整块面板:看完账多半还要接着说话,把人从输入框里赶出去是多余的一步。
 *
 * <p>输入按<b>缓存命中 / 新处理</b>拆,这是唯一与服务商无关的拆法;缓存写归到新处理
 * 那一侧,它是实打实处理过的量,只是顺便存了起来。页脚那一行读的是同一份数据
 * ({@link EntityAgentLoop#usageTotals()} 等),不各算各的。
 */
final class UsageCommand implements PopupCommand {

    @Override
    public String name() {
        return "usage";
    }

    @Override
    public String description() {
        return I18n.get(Keys.CMD_USAGE);
    }

    @Override
    public Popup popup(EntityAgentLoop loop) {
        return new Readout(new UsageContent(loop));
    }

    /** 每次取值都现问:账在她说话时随时在变。 */
    private record UsageContent(EntityAgentLoop loop) implements Readout.Content {

        @Override
        public String title() {
            return I18n.get(Keys.USAGE_TITLE);
        }

        /** 命中是"好"的那段,新处理是中性的。服务商不报缓存就没有构成可言。 */
        @Override
        public List<Readout.Part> bar() {
            Usage u = loop.usageTotals();
            if (!u.reportsCache()) return List.of();
            return List.of(
                    new Readout.Part(u.cacheRead(), Readout.Tone.GOOD),
                    new Readout.Part(u.input() + u.cacheWrite(), Readout.Tone.PLAIN));
        }

        @Override
        public List<Readout.Line> lines() {
            Usage u = loop.usageTotals();
            List<Readout.Line> out = new ArrayList<>();
            if (u.total() <= 0) {
                out.add(Readout.Line.of(I18n.get(Keys.USAGE_NONE), I18n.get(Keys.USAGE_NONE_HINT)));
                return out;
            }
            out.add(Readout.Line.of(I18n.get(Keys.USAGE_INPUT), group(u.promptTokens())));
            if (u.reportsCache()) {
                out.add(Readout.Line.sub(I18n.get(Keys.USAGE_CACHE_HIT), group(u.cacheRead()),
                        TokenFormat.percent1(u.cacheHitRate()) + "%"));
                out.add(Readout.Line.sub(I18n.get(Keys.USAGE_FRESH), group(u.input() + u.cacheWrite()), null));
                if (u.cacheWrite() > 0) {
                    out.add(Readout.Line.sub(I18n.get(Keys.USAGE_CACHE_WRITE), group(u.cacheWrite()), null));
                }
            }
            out.add(Readout.Line.of(I18n.get(Keys.USAGE_OUTPUT), group(u.output())));
            out.add(Readout.Line.of(I18n.get(Keys.USAGE_TOTAL), group(u.total())));
            double last = loop.lastUsage().cacheHitRate();
            if (u.reportsCache() && last >= 0) {
                // 低了说明前缀正在被打穿——数字自己带颜色
                out.add(Readout.Line.toned(I18n.get(Keys.USAGE_LAST_HIT), TokenFormat.percent1(last) + "%",
                        last >= 0.7 ? Readout.Tone.GOOD : Readout.Tone.WARN));
            }
            return out;
        }

        /** 这条出现就是前缀被动过:提示词改了、工具清单变了、或缓存过期。 */
        @Override
        public String alert() {
            CacheWaste waste = loop.cacheWaste();
            if (waste.missedTokens() <= 0) return null;
            return I18n.get(Keys.USAGE_WASTE, group(waste.missedTokens()), waste.missCount());
        }

        /** 千分位:账要看得出量级,不缩写。 */
        private static String group(long n) {
            return String.format(Locale.ROOT, "%,d", n);
        }
    }
}
