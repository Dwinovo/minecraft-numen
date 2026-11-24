package com.dwinovo.numen.permission;

/**
 * 档位:主人给一种动作定的"没人说话时怎么办",也是一次裁决最后的结果——许、问、不许。
 * 次序就是严的程度:{@link #ALLOW} 最松,{@link #DENY} 最严。{@link #word} 是存档、命令与面板里写的那个词。
 */
public enum Tier {
    ALLOW("allow"), ASK("ask"), DENY("deny");

    private final String word;

    Tier(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }

    /** 比 {@code other} 严。 */
    public boolean stricterThan(Tier other) {
        return ordinal() > other.ordinal();
    }

    /** 词 → 档位;认不出返回 null。 */
    public static Tier byWord(String word) {
        for (Tier t : values()) {
            if (t.word.equals(word)) {
                return t;
            }
        }
        return null;
    }
}
