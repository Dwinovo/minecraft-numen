package com.dwinovo.numen.agent.conversation;

/**
 * 引用回复(Telegram 的"回复"):主人指着某一句接着说。发出去的是一整段纯文本——第一行
 * {@code > 谁: 那句},下面是主人的话。模型读得懂,落盘就是原样;对话流把第一行画成气泡顶上的引用条,
 * 左栏预览和 @ 判谁回都只看正文。这个格式只在这里定义:拼和拆都在这儿。
 *
 * @param who     引的是谁的话;不是引用时为 null
 * @param snippet 引的那句(压成一行、截短过);不是引用时为 null
 * @param body    主人自己说的话;不是引用时是原文
 */
public record Quote(String who, String snippet, String body) {

    private static final String PREFIX = "> ";
    private static final String SEP = ": ";
    /** 引的那句最多留这么多字:引用只是指一下是哪句,不是把整段再发一遍。 */
    static final int SNIPPET_MAX = 60;

    /** 拼:引的那句压成一行、截到 {@link #SNIPPET_MAX} 字,放在第一行;主人的话在下面。 */
    public static String compose(String who, String quoted, String body) {
        String flat = quoted.replace('\r', ' ').replace('\n', ' ').strip();
        if (flat.length() > SNIPPET_MAX) flat = flat.substring(0, SNIPPET_MAX) + "…";
        return PREFIX + who + SEP + flat + "\n" + body;
    }

    /** 拆:第一行是 {@code > 谁: 那句} 就拆出来;否则整段是正文。 */
    public static Quote parse(String text) {
        if (text == null) return new Quote(null, null, "");
        int nl = text.indexOf('\n');
        if (!text.startsWith(PREFIX) || nl < 0) return new Quote(null, null, text);
        String head = text.substring(PREFIX.length(), nl);
        int sep = head.indexOf(SEP);
        if (sep <= 0) return new Quote(null, null, text);
        return new Quote(head.substring(0, sep), head.substring(sep + SEP.length()), text.substring(nl + 1));
    }

    /** 这一句是不是在回别的哪句。 */
    public boolean quoted() {
        return who != null;
    }
}
