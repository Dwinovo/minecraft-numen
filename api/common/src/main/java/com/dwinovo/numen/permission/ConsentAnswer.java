package com.dwinovo.numen.permission;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次征询的结论。
 *
 * @param decision 主人按的哪个键;超时、主人不在、被顶替、任务先结束都按 {@link Decision#DENY}
 * @param words    主人的附言原话;拒绝时没有附言,是登记处替这次结局说的那句
 *                 ({@link ConsentDesk#OWNER_SAID_NO} 等)
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
     * 允许之后回执里交代的那一句:主人点了头、说了什么;选的是"允许并记住"时再交代记下了哪几行规则,
     * 往后同类的事不再问。
     */
    public String allowance(List<ConsentItem> asked) {
        StringBuilder sb = new StringBuilder("the owner allowed: ").append(ConsentItem.listingText(asked));
        if (!words.isEmpty()) {
            sb.append(" (owner's note: ").append(words).append(')');
        }
        if (decision == Decision.ALLOW_REMEMBER) {
            List<String> rules = new ArrayList<>();
            for (Rule rule : ConsentItem.remembered(asked)) {
                rules.add("allow " + rule);
            }
            sb.append("; and remembered it, so these will not be asked again: ").append(String.join("; ", rules));
        }
        return sb.toString();
    }
}
