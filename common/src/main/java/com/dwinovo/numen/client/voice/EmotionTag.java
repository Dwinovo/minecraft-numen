package com.dwinovo.numen.client.voice;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 剥掉文本里的方括号情绪标签(如 {@code [joy]})。情绪驱动 TTS 的功能已废除
 * (拖慢合成且效果不稳),这个类只留卫生职责:旧对话历史里存着标签、模型
 * 也可能模仿历史输出——展示与朗读前都得剥干净,不能把"方括号 joy"念出来。
 */
public final class EmotionTag {

    /** 方括号标签:2~16 个英文字母。 */
    private static final Pattern TAG = Pattern.compile("\\[([A-Za-z]{2,16})\\]");

    private EmotionTag() {}

    /** 剥掉全部 {@code [词]} 标签,返回干净正文。 */
    public static String strip(String raw) {
        if (raw == null || raw.isEmpty() || raw.indexOf('[') < 0) {
            return raw == null ? "" : raw;
        }
        Matcher m = TAG.matcher(raw);
        return m.replaceAll("");
    }
}
