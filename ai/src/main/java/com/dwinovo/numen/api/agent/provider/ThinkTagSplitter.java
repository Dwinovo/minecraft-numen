package com.dwinovo.numen.api.agent.provider;

/**
 * 把正文流里用 {@code <think>…</think>} 包着的思考分流出去。
 *
 * <p>有的推理模型(DeepSeek-R1 蒸馏系经 LM Studio/Ollama 的兼容接口)不走
 * {@code reasoning_content} 字段,直接把思考写在 content 里。这里是它们进入"思考通道"的唯一入口:
 * 标签之内进思考,标签之外进正文,和方言字段的思考落在同一个 {@link StreamAccumulator#reasoning}。
 *
 * <p>标签可能被切在两个 chunk 之间(如 {@code "<thi"} + {@code "nk>"}),所以每次把"可能是
 * 标签开头的尾巴"扣下,等下一段来了再判;流结束时 {@link #flush} 把扣着的尾巴按当时所在通道放出。
 * 思考没闭合就一直算思考(流式中途的常态)。
 * 收起标签后紧跟的空白不要:模型惯在 {@code </think>} 后空两行再开口。
 */
final class ThinkTagSplitter {

    private static final String OPEN = "<think>";
    private static final String CLOSE = "</think>";

    private boolean inThink;
    /** 刚收起思考:正文开头的空白丢掉,直到第一个实字。 */
    private boolean skipLeadingBlank;
    /** 扣下的、可能是标签开头的尾巴。 */
    private String held = "";

    /** 喂一段 content 增量;正文与思考各自追加到传入的缓冲。 */
    void feed(String fragment, StringBuilder visible, StringBuilder thought) {
        String buf = held + fragment;
        held = "";
        while (!buf.isEmpty()) {
            String tag = inThink ? CLOSE : OPEN;
            int at = buf.indexOf(tag);
            if (at >= 0) {
                emit(buf.substring(0, at), visible, thought);
                buf = buf.substring(at + tag.length());
                inThink = !inThink;
                skipLeadingBlank = !inThink;
                continue;
            }
            int keep = tailThatStartsTag(buf, tag);
            emit(buf.substring(0, buf.length() - keep), visible, thought);
            held = buf.substring(buf.length() - keep);
            return;
        }
    }

    /** 流结束:扣着的尾巴不会再成标签了,原样放出。 */
    void flush(StringBuilder visible, StringBuilder thought) {
        emit(held, visible, thought);
        held = "";
    }

    private void emit(String text, StringBuilder visible, StringBuilder thought) {
        if (text.isEmpty()) return;
        if (inThink) {
            thought.append(text);
            return;
        }
        if (skipLeadingBlank) {
            text = text.stripLeading();
            if (text.isEmpty()) return;
            skipLeadingBlank = false;
        }
        visible.append(text);
    }

    /** buf 末尾最长的、是 tag 真前缀的那段长度。 */
    private static int tailThatStartsTag(String buf, String tag) {
        int max = Math.min(buf.length(), tag.length() - 1);
        for (int n = max; n > 0; n--) {
            if (tag.startsWith(buf.substring(buf.length() - n))) return n;
        }
        return 0;
    }
}
