package com.dwinovo.numen.client.chat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 看<b>对话</b>:主人和她说过的话,别的都不是这一份的内容。
 * <ul>
 *   <li><b>主人消息</b>:只取 {@code <query>} 里的原话。未打标的旧消息剥掉注入块
 *       ({@code <persona-change>}/{@code <event>})后展示,纯注入的显示为空
 *       (调用方跳过);</li>
 *   <li><b>同伴消息</b>:折叠段落空行(模型的排版习惯,面板寸土寸金)。</li>
 * </ul>
 *
 * <p>剥记号不是这份视图的目的,是它的后果——协议记号本来就不属于"他俩的对话"。
 * 要看那些东西,换 {@link RawRequestMode}。
 */
public final class OwnerWordsMode implements ChatDisplayMode {

    private static final Pattern QUERY = Pattern.compile("(?s)<query>(.*?)</query>");

    @Override
    public String userText(String raw) {
        if (raw == null) return "";
        Matcher m = QUERY.matcher(raw);
        StringBuilder b = new StringBuilder();
        while (m.find()) {
            if (b.length() > 0) b.append('\n');
            b.append(m.group(1));
        }
        if (b.length() > 0) return b.toString().strip();
        return stripInjectedDirectives(raw);   // legacy / untagged owner message
    }

    @Override
    public String assistantText(String raw) {
        if (raw == null) return "";
        // 段落间的空行折叠成单换行——聊天面板寸土寸金,空行只是模型的排版习惯。
        return raw.replaceAll("\\n\\s*\\n+", "\n").strip();
    }

    /**
     * Strip numen-injected directive blocks ({@code <persona-change>…</persona-change>},
     * {@code <event …>…</event>}) from a user message so only the owner's own words show in chat.
     * The full message (directives included) is still what the LLM receives — display-only.
     */
    private static String stripInjectedDirectives(String s) {
        // <events> 是事件的分组包装(EventQueue.drain 发的),必须单独剥、且排在剥单条 <event>
        // 之前。别指望 <event\b 顺手带走它——"events" 在 t 与 s 之间没有词边界,那条规则
        // 漏掉整个包装,面板上就剩下字面的 <events></events> 两行(主人会以为在发空事件)。
        String out = s.replaceAll("(?s)<events>.*?</events>", "")
                .replaceAll("(?s)<persona-change>.*?</persona-change>", "")
                .replaceAll("(?s)<event\\b[^>]*>.*?</event>", "")
                .replaceAll("(?s)<event\\b[^>]*/>", "")
                .replaceAll("(?s)<env>.*?</env>", "")
                .replaceAll("(?s)<current_task>.*?</current_task>", "")
                .replaceAll("(?s)<known_blocks>.*?</known_blocks>", "");
        return out.replaceAll("\\n\\s*\\n+", "\n").strip();
    }
}
