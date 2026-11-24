package com.dwinovo.numen.permission;

/**
 * 八个档位的预设。{@link #OBSERVE} 全定成不许、{@link #BYPASS} 全定成许,两者只动档位、不动名单;
 * {@link #FACTORY} 恢复出厂:档位回到出厂的档,名单清空。
 */
public enum Preset {
    OBSERVE("observe"), BYPASS("bypass"), FACTORY("factory");

    private final String word;

    Preset(String word) {
        this.word = word;
    }

    public String word() {
        return word;
    }

    /** 词 → 预设;认不出返回 null。 */
    public static Preset byWord(String word) {
        for (Preset p : values()) {
            if (p.word.equals(word)) {
                return p;
            }
        }
        return null;
    }

    Settings applyTo(Settings settings) {
        return switch (this) {
            case OBSERVE -> settings.withAllTiers(Tier.DENY);
            case BYPASS -> settings.withAllTiers(Tier.ALLOW);
            case FACTORY -> Settings.FACTORY;
        };
    }
}
