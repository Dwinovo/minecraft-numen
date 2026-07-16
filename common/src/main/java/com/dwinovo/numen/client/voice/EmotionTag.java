package com.dwinovo.numen.client.voice;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 语音情绪标签:提示词引导 LLM 在句首用 {@code [词]} 标注语气(词汇自由,
 * 英文小写单词即可)。管线把标签原样摘出来交给
 * {@link TtsBackend#synthesize(String, String)},各后端自行决定怎么用——
 * 换个括号格式透传、映射到自家参数、或者不认识就忽略。核心不维护词表。
 */
public final class EmotionTag {

    /** 方括号标签:2~16 个英文字母。 */
    private static final Pattern TAG = Pattern.compile("\\[([A-Za-z]{2,16})\\]");

    /** 摘取结果:第一个标签词(小写,没有则 null)+ 剥掉全部标签后的文本。 */
    public record Tagged(String emotion, String text) {}

    private EmotionTag() {}

    /** 摘出句段里第一个 {@code [词]} 标签,并剥掉所有同格式标签(不能被念出来)。 */
    public static Tagged extract(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('[') < 0) {
            return new Tagged(null, raw == null ? "" : raw);
        }
        String emotion = null;
        StringBuilder out = new StringBuilder(raw.length());
        Matcher m = TAG.matcher(raw);
        int last = 0;
        while (m.find()) {
            out.append(raw, last, m.start());
            last = m.end();
            if (emotion == null) {
                emotion = m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        out.append(raw, last, raw.length());
        return new Tagged(emotion, out.toString());
    }
}
