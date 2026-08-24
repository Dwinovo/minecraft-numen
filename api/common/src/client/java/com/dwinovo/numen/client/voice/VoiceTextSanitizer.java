package com.dwinovo.numen.client.voice;

import java.util.regex.Pattern;

/**
 * 合成前的文本清洗：把 LLM 回复里"能看不能读"的记号剥掉，只留下适合
 * 张嘴念出来的部分。作用在 {@link SentenceDivider} 切出的单个句段上。
 *
 * <h2>清洗内容</h2>
 * <ul>
 *   <li>XML/HTML 标签残留（{@code <event>}、{@code </summary>} 等注入协议的边角）；</li>
 *   <li>URL（http/https/www 开头的一整串）；</li>
 *   <li>括号动作/神态描写——半角与全角括号包住的短片段（"(笑)"、"（挥手）"），
 *       以及星号包住的动作（"*点头*"）。角色扮演式输出很常见；</li>
 *   <li>markdown 记号：粗体/斜体星号与下划线、行内代码反引号、标题 #、
 *       删除线、行首引用符 &gt;；</li>
 *   <li>清完后压缩空白。</li>
 * </ul>
 *
 * <p>没有任何可读内容（字母/数字/CJK 全无）时返回空串，调用方据此跳过该段，
 * 不浪费一次 TTS 请求。
 *
 * <p>纯函数、无 Minecraft 依赖，headless JUnit 直接测。
 */
public final class VoiceTextSanitizer {

    private VoiceTextSanitizer() {}

    /** XML/HTML 标签（限长防灾难回溯；跨段的残缺标签清不掉,属已知边界）。 */
    private static final Pattern XML_TAG = Pattern.compile("</?[A-Za-z][^<>]{0,80}>");
    /** URL：协议头或 www. 开头的连续非空白。 */
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)\\S+");
    /** 括号动作描写：半角/全角括号包住的短片段（≤40 字符,不跨行）。 */
    private static final Pattern PAREN_ACTION = Pattern.compile("[(（][^()（）\\n]{0,40}[)）]");
    /** 星号动作描写：*挥手*（≤40 字符,不跨行）。markdown 粗体的 ** 先于此规则被剥掉。 */
    private static final Pattern STAR_ACTION = Pattern.compile("\\*[^*\\n]{1,40}\\*");
    /** 残余的 markdown 记号字符。 */
    private static final Pattern MD_MARKS = Pattern.compile("(\\*\\*|__|~~|[*_`#])");
    /** 行首引用符。 */
    private static final Pattern QUOTE_MARK = Pattern.compile("(?m)^\\s*>+\\s?");
    /** 方括号记号标签:清掉 [thinking] 这类模型自造记号,别让 TTS 念出来。 */
    private static final Pattern BRACKET_TAG = Pattern.compile("\\[[A-Za-z]{2,12}\\]");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * 清洗一个句段。返回可直接送 TTS 的文本；无可读内容时返回 {@code ""}。
     */
    public static String clean(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String s = raw;
        s = XML_TAG.matcher(s).replaceAll(" ");
        s = URL.matcher(s).replaceAll(" ");
        s = PAREN_ACTION.matcher(s).replaceAll(" ");
        s = s.replace("**", "");             // 先剥粗体记号,免得被星号动作规则整段吃掉
        s = STAR_ACTION.matcher(s).replaceAll(" ");
        s = QUOTE_MARK.matcher(s).replaceAll("");
        s = BRACKET_TAG.matcher(s).replaceAll(" ");
        s = MD_MARKS.matcher(s).replaceAll("");
        s = WHITESPACE.matcher(s).replaceAll(" ").strip();
        return hasSpeakable(s) ? s : "";
    }

    /** 是否含有任何能念出来的字符（字母/数字/CJK）。纯标点段视为空。 */
    private static boolean hasSpeakable(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) return true;
        }
        return false;
    }
}
