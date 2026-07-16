package com.dwinovo.numen.client.chat;

import com.dwinovo.numen.client.voice.EmotionTag;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 默认显示过滤:把协议记号从玩家看到的文本里剥掉。
 * <ul>
 *   <li><b>主人消息</b>:{@code <query>} 包着的才是主人的原话,只显示它;
 *       未打标的旧消息剥掉注入指令块({@code <persona-change>}/{@code <event>})
 *       后展示,纯注入消息显示为空(调用方跳过);</li>
 *   <li><b>同伴消息</b>:剥掉 {@code [emotion]} 语音情绪标签(它们是给 TTS 的
 *       语气指令,不属于正文)。</li>
 * </ul>
 */
public final class DefaultChatDisplayFilter implements ChatDisplayFilter {

    private static final Pattern QUERY = Pattern.compile("(?s)<query>(.*?)</query>");

    @Override
    public String ownerText(String raw) {
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
    public String companionText(String raw) {
        if (raw == null) return "";
        return EmotionTag.extract(raw).text().strip();
    }

    /**
     * Strip numen-injected directive blocks ({@code <persona-change>…</persona-change>},
     * {@code <event …>…</event>}) from a user message so only the owner's own words show in chat.
     * The full message (directives included) is still what the LLM receives — display-only.
     */
    private static String stripInjectedDirectives(String s) {
        String out = s.replaceAll("(?s)<persona-change>.*?</persona-change>", "")
                .replaceAll("(?s)<event\\b[^>]*>.*?</event>", "")
                .replaceAll("(?s)<event\\b[^>]*/>", "");
        return out.strip();
    }
}
