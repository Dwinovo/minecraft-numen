package com.dwinovo.numen.permission;

/** 规则里的动作:身体对世界能做的八件事。{@link #word} 是规则文本里写的那个词。 */
public enum Verb {
    BREAK("break", true), PLACE("place", true), ATTACK("attack", false), USE_BLOCK("use_block", true),
    USE_ENTITY("use_entity", false), TAKE("take", true), DROP("drop", false), COMMAND("command", false);

    private final String word;
    private final boolean atBlock;

    Verb(String word, boolean atBlock) {
        this.word = word;
        this.atBlock = atBlock;
    }

    /** 规则文本里的动词。 */
    public String word() {
        return word;
    }

    /** 这种动作落在一格上:挖、放、右键方块、从容器拿。 */
    public boolean atBlock() {
        return atBlock;
    }

    /** 规则文本里的动词 → 动词;认不出返回 null。 */
    public static Verb byWord(String word) {
        for (Verb v : values()) {
            if (v.word.equals(word)) {
                return v;
            }
        }
        return null;
    }
}
