package com.dwinovo.numen.client.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 增量分句器：流式喂入 LLM 文本 delta，尽早吐出"可送去合成的句段"。
 * 目标是首句开口延迟——首段允许在逗号级标点就切出去，后续段只在
 * 句末标点切，兼顾开口速度和语音自然度。
 *
 * <h2>切分规则</h2>
 * <ul>
 *   <li><b>首段</b>：遇到首个句读（逗号/顿号/句号/叹号/问号/分号/冒号/省略号/换行）
 *       且累计长度 ≥ {@link #MIN_FIRST_SEGMENT_CHARS} 即切——让 TTS 尽快开口；</li>
 *   <li><b>后续段</b>：只在句末标点（。．！？…！?.\n）切，避免把一句话读得支离破碎；</li>
 *   <li><b>无标点保险</b>：缓冲超过 {@link #MAX_SEGMENT_CHARS} 仍无边界则强制切，
 *       模型偶尔输出一长串无标点文本时不至于永远攒着不播；</li>
 *   <li><b>前瞻消歧</b>：ASCII 标点需要看一眼下一个字符才敢切——
 *       小数点（"3.14"）、英文缩写（"e.g."、"Mr."）、URL 里的点都不是句界。
 *       此外任何切点若恰好落在缓冲末尾（换行除外）,都会多等一个 delta,
 *       以便把紧随其后的闭合引号/后续省略号并进本段；</li>
 *   <li><b>收尾</b>：流结束后调用 {@link #flush()} 取出余量（此时 ASCII 标点
 *       不再等前瞻，缓冲里剩什么吐什么）。</li>
 * </ul>
 *
 * <h2>纯函数性</h2>
 * 不依赖任何 Minecraft / 网络类，单线程使用（调用方保证在主线程喂入），
 * headless JUnit 可直接测。
 */
public final class SentenceDivider {

    /** 首段的最短长度（含标点）——太短的"好,"开口反而突兀。 */
    public static final int MIN_FIRST_SEGMENT_CHARS = 10;

    /** 后续段的最短长度:不足则并入下一段——"好。""嗯!"这类超短句独立成段
     *  会多烧一次 TTS 请求,听感也碎。 */
    public static final int MIN_SENTENCE_CHARS = 10;

    /** 无标点保险丝：缓冲超过这个长度还找不到边界就强制切。 */
    public static final int MAX_SEGMENT_CHARS = 60;

    /** 首段允许的切分标点：句读级（逗号、顿号也算）。 */
    private static final String FIRST_BOUNDARY = ",，、.。．!！?？;；:：…\n";

    /** 后续段允许的切分标点：仅句末级。 */
    private static final String END_BOUNDARY = ".。．!！?？…\n";

    /** 切点之后允许顺带吸收的收尾字符（引号/括号闭合、重复的省略号等）。 */
    private static final String TRAILING_ATTACH = "”’\"'」』）)…。！？!?";

    /**
     * '.' 前的常见英文缩写（小写、不含结尾点）。命中则该点不是句界。
     * 参数取自常见 TTS 分句实现的缩写清单，只保留高频项。
     */
    private static final Set<String> ABBREVIATIONS = Set.of(
            "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "e.g", "i.e");

    private final StringBuilder buffer = new StringBuilder();
    /** 首段已经吐出过（之后进入"仅句末标点"模式）。 */
    private boolean firstEmitted;
    /** 攒着待并入下一段的超短句(后续段专用;flush 时兜底吐出)。 */
    private String carry = "";

    /**
     * 喂入一个流式 delta，返回本次新产生的完整句段（可能为空）。
     * 返回的句段已 strip；调用方仍需过一遍文本清洗。
     */
    public List<String> feed(String delta) {
        if (delta == null || delta.isEmpty()) return List.of();
        buffer.append(delta);
        return drain(false);
    }

    /**
     * 流结束：吐出所有还能按规则切出的句段 + 缓冲余量，并重置状态
     * （下一轮回复从"首段"重新开始）。
     */
    public List<String> flush() {
        List<String> out = drain(true);
        String rest = (carry + buffer.toString()).strip();
        if (!rest.isEmpty()) out.add(rest);
        reset();
        return out;
    }

    /** 丢弃缓冲并回到初始状态（新 turn / 打断时调用）。 */
    public void reset() {
        buffer.setLength(0);
        firstEmitted = false;
        carry = "";
    }

    /** 当前缓冲里还攒着多少字符（测试与调试用）。 */
    public int pendingChars() {
        return buffer.length();
    }

    // ---- internals ----

    private List<String> drain(boolean atEnd) {
        List<String> out = new ArrayList<>();
        while (true) {
            int cut = findCut(atEnd);
            if (cut < 0) break;
            String segment = buffer.substring(0, cut).strip();
            int rest = cut;
            while (rest < buffer.length() && Character.isWhitespace(buffer.charAt(rest))) rest++;
            buffer.delete(0, rest);
            if (segment.isEmpty()) continue;
            // 后续段的超短句并入下一段(首段不并,开口速度优先;流结束不并,直接吐)。
            String candidate = carry.isEmpty() ? segment : carry + segment;
            if (firstEmitted && !atEnd && candidate.length() < MIN_SENTENCE_CHARS) {
                carry = candidate;
                continue;
            }
            carry = "";
            out.add(candidate);
            firstEmitted = true;
        }
        return out;
    }

    /**
     * 在缓冲的前 {@link #MAX_SEGMENT_CHARS} 个字符里找一个可切点，
     * 返回句段的 <b>不含端</b> 下标；找不到返回 -1。
     */
    private int findCut(boolean atEnd) {
        int n = buffer.length();
        if (n == 0) return -1;
        int scanLimit = Math.min(n, MAX_SEGMENT_CHARS);
        String boundaries = firstEmitted ? END_BOUNDARY : FIRST_BOUNDARY;
        for (int i = 0; i < scanLimit; i++) {
            char c = buffer.charAt(i);
            if (boundaries.indexOf(c) < 0) continue;
            if (!firstEmitted && i + 1 < MIN_FIRST_SEGMENT_CHARS) continue;
            if (c < 0x80 && c != '\n' && !asciiBoundaryConfirmed(i, atEnd)) continue;
            int cutEnd = absorbTrailing(i);
            // 切点吸收到了缓冲末尾:流没结束就再等一个 delta——下一个字符可能
            // 是要并入本段的闭合引号/后续省略号("……"逐字到达时不能在第一个
            // '…' 就切)。换行例外:换行后不会有归属本段的字符。
            if (cutEnd >= n && !atEnd && c != '\n') break;
            return cutEnd;
        }
        // 保险丝：无边界且已攒满,硬切(避免劈开代理对)。
        if (n >= MAX_SEGMENT_CHARS) {
            int cut = MAX_SEGMENT_CHARS;
            if (Character.isHighSurrogate(buffer.charAt(cut - 1))) cut--;
            return cut;
        }
        return -1;
    }

    /**
     * ASCII 标点的前瞻消歧。规则（全部有测试）：
     * <ul>
     *   <li>两侧都是数字 → 小数/时间/比分（"3.14"、"1:30"），不切；</li>
     *   <li>'.' 后还是 '.' → 省略号中段，等最后一个点；</li>
     *   <li>'.' 且前面是已知英文缩写或单个大写字母（人名缩写）→ 不切；</li>
     *   <li>下一个字符必须是空白、CJK 或闭合引号/括号才算句界——
     *       这挡住了 "www.foo.com"、"1:30pm" 这类内嵌标点；</li>
     *   <li>标点已是缓冲最后一个字符：流没结束就先等下一个 delta（无从前瞻），
     *       {@code flush()} 时直接算句界。</li>
     * </ul>
     */
    private boolean asciiBoundaryConfirmed(int i, boolean atEnd) {
        char c = buffer.charAt(i);
        int n = buffer.length();
        if (i > 0 && i + 1 < n
                && Character.isDigit(buffer.charAt(i - 1))
                && Character.isDigit(buffer.charAt(i + 1))) {
            return false;
        }
        if (c == '.' && i + 1 < n && buffer.charAt(i + 1) == '.') return false;
        if (c == '.' && isAbbreviationBefore(i)) return false;
        if (i + 1 >= n) return atEnd;   // 前瞻不足,等下一个 delta
        char next = buffer.charAt(i + 1);
        return Character.isWhitespace(next) || next > 0x2E7F
                || TRAILING_ATTACH.indexOf(next) >= 0;
    }

    /** '.' 前的词是否是英文缩写（或单个大写字母的人名缩写）。 */
    private boolean isAbbreviationBefore(int dotIndex) {
        int start = dotIndex;
        while (start > 0) {
            char p = buffer.charAt(start - 1);
            if (Character.isLetter(p) && p < 0x80 || p == '.') start--;
            else break;
        }
        if (start == dotIndex) return false;
        String token = buffer.substring(start, dotIndex);
        if (token.length() == 1 && Character.isUpperCase(token.charAt(0))) return true;   // "J. Smith"
        return ABBREVIATIONS.contains(token.toLowerCase(java.util.Locale.ROOT));
    }

    /** 把切点后紧跟的闭合引号/重复标点一并并入本段，返回不含端下标。 */
    private int absorbTrailing(int boundaryIndex) {
        int j = boundaryIndex + 1;
        int n = buffer.length();
        while (j < n && TRAILING_ATTACH.indexOf(buffer.charAt(j)) >= 0) j++;
        return j;
    }
}
