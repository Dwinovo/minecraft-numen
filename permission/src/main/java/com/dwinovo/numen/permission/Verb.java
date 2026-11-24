package com.dwinovo.numen.permission;

/**
 * 身体对世界能做的八种事。{@link #word} 是存档、命令与面板里写的那个词,{@link #factory} 是出厂时主人给这种事定的档。
 * 次序就是面板上排的次序。
 */
public enum Verb {
    BREAK("break", true, Tier.ALLOW), PLACE("place", true, Tier.ALLOW), USE_BLOCK("use_block", true, Tier.ALLOW),
    ATTACK("attack", false, Tier.ALLOW), USE_ENTITY("use_entity", false, Tier.ALLOW), DROP("drop", false, Tier.ASK),
    USE_ITEM("use_item", false, Tier.ALLOW), COMMAND("command", false, Tier.ASK);

    private final String word;
    private final boolean atBlock;
    private final Tier factory;

    Verb(String word, boolean atBlock, Tier factory) {
        this.word = word;
        this.atBlock = atBlock;
        this.factory = factory;
    }

    public String word() {
        return word;
    }

    /** 这种动作落在一格上:挖、放、右键方块。 */
    public boolean atBlock() {
        return atBlock;
    }

    /** 出厂的档。 */
    public Tier factory() {
        return factory;
    }

    /** 词 → 动作;认不出返回 null。 */
    public static Verb byWord(String word) {
        for (Verb v : values()) {
            if (v.word.equals(word)) {
                return v;
            }
        }
        return null;
    }
}
