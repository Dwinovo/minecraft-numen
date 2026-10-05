package com.dwinovo.numen.agent.conversation;

import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code <audience turn="17">阿岚、小梅</audience>}——挂在 {@code <query>} 后面那一行:还有谁听得见,
 * 以及这是会话里的第几句(见 {@link Conversation#turn})。拼和认都在这一处,免得写的和读的跑偏。
 */
public final class Audience {

    private static final Pattern TURN = Pattern.compile("<audience turn=\"(\\d+)\">");

    private Audience() {}

    /** @param escapedNames 已经做过 XML 转义的名字 */
    public static String line(int turn, String escapedNames) {
        return "<audience turn=\"" + turn + "\">" + escapedNames + "</audience>";
    }

    /** 这条 user 消息里那句话的发言号;没挂 audience(就他俩)则空。 */
    public static OptionalInt turnOf(String content) {
        if (content == null) {
            return OptionalInt.empty();
        }
        Matcher m = TURN.matcher(content);
        return m.find() ? OptionalInt.of(Integer.parseInt(m.group(1))) : OptionalInt.empty();
    }
}
