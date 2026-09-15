package com.dwinovo.numen.permission;

import java.util.List;

/**
 * 一次征询的结论。
 *
 * <p>主人答复时带的附言当场到她那里:拒绝的附言就是这里的 {@code words},随发起的任务收场一起送达;
 * 允许了任务还在接着干,附言等不到收场,由登记处当场作为 {@code consent_note} 事件转过去
 * ({@link ConsentDesk#answer}),不进这里。
 *
 * @param decision 主人按的哪个键;超时、主人不在、被顶替、任务先结束都按 {@link Decision#DENY}
 * @param words    拒绝的理由:主人的附言原话,没有附言时是登记处替这次结局说的那句
 *                 ({@link ConsentDesk#OWNER_SAID_NO} 等);允许时为空串
 */
public record ConsentAnswer(Decision decision, String words) {

    /** 主人的三种答复。 */
    public enum Decision {
        /** 允许:只在发起这次征询的任务里有效。 */
        ALLOW_ONCE,
        /** 允许并记住:本任务里放行,并把每一条的 {@link ConsentItem#remember} 写进主人的 allow 表。 */
        ALLOW_REMEMBER,
        DENY
    }

    public ConsentAnswer {
        words = words == null ? "" : words;
    }

    public boolean allowed() {
        return decision != Decision.DENY;
    }

    /** 拒绝的回执正文:主人的原话,后面带上问的是什么。征询被拒的 {@code REFUSED} 理由只从这里出。 */
    public String refusal(List<ConsentItem> asked) {
        return "refused by the owner: " + words + " (asked: " + ConsentItem.listingText(asked) + ")";
    }

    /**
     * 允许之后回执里交代的那一句:主人点了头;选的是"允许并记住"时再交代记下了哪几行规则,往后同类的事不再问。
     */
    public String allowance(List<ConsentItem> asked) {
        StringBuilder sb = new StringBuilder("the owner allowed: ").append(ConsentItem.listingText(asked));
        if (decision == Decision.ALLOW_REMEMBER) {
            sb.append("; and remembered it, so these will not be asked again: ")
                    .append(String.join("; ", ConsentItem.rememberedRows(asked)));
        }
        return sb.toString();
    }
}
