package com.dwinovo.numen.api.agent.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 主人的话在 user 消息里的记号 {@code <query>}:怎么写、怎么认都只在这一处,面板剥记号、归并去重、认说话的人都从这里取,别各写一份正则。
 */
public final class QueryMarkup {

    private static final Pattern QUERY = Pattern.compile("(?s)<query(?: [^>]*)?>(.*?)</query>");
    private static final Pattern QUERY_TAG = Pattern.compile("<query((?: [^>]*)?)>");

    /** 一句话来自主人给她定的定时({@code <query>} 开标签上多的那个属性,带前面的空格);认它也只在这一处,见 {@link #scheduled}。 */
    public static final String SCHEDULED_ATTR = " source=\"schedule\"";

    private QueryMarkup() {}

    /**
     * 一句话是哪位主人说的:{@code <query>} 开标签上多的那个属性(带前面的空格),值是他写给模型读的身份({@code player@短码 (名字)})。
     * 主人的话每一句都带,单个主人也一样;写在这一处,从条目上记的说话人来,不从文字里读回。
     */
    public static String speakerAttr(String speaker) {
        return " from=\"" + speaker.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;")
                + "\"";
    }

    /**
     * 一句话是在哪儿说的:{@code <query>} 开标签上多的那个属性(带前面的空格)。{@code venue} 是 {@code whisper}(私聊她)、
     * {@code group}(她所在的群里)之一;面板说的话(默认)不带。群里还有谁听得见由 {@code <audience>} 那一行说({@code Audience})。
     */
    public static String venueAttr(String venue) {
        return " venue=\"" + venue + "\"";
    }

    /** 写给模型读的说话人({@code player@短码 (名字)},名字可以没有)里的身份部分({@code player@短码}):记录里的 {@code from} 只存它。 */
    public static String identityOf(String shown) {
        int name = shown.indexOf(" (");
        return name < 0 ? shown : shown.substring(0, name);
    }

    /** 一条 user 消息里主人的原话——{@code <query>} 标记里的那些,按出现顺序;没有标记则空。 */
    public static List<String> queries(String content) {
        List<String> out = new ArrayList<>();
        if (content == null) return out;
        Matcher m = QUERY.matcher(content);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    /**
     * 这条 user 消息里的主人的话是不是全部来自定时({@link #SCHEDULED_ATTR}):面板据此在这一句前标"定时"。
     * 夹着主人亲口说的话(没有标)的不算——那一条气泡里有他自己的话,不能整条说成定时。没有 {@code <query>} 的不算。
     */
    public static boolean scheduled(String content) {
        if (content == null) return false;
        Matcher m = QUERY_TAG.matcher(content);
        boolean any = false;
        while (m.find()) {
            if (!m.group(1).contains(SCHEDULED_ATTR)) return false;
            any = true;
        }
        return any;
    }
}
