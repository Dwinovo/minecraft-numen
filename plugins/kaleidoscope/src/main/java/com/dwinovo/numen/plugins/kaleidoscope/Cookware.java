package com.dwinovo.numen.plugins.kaleidoscope;

/**
 * 森罗的两口锅——三个工具的参数、回执与事件属性都用这两个词。
 *
 * <p>第一版只有炒锅和汤锅;蒸笼、砧板、石磨、烤架还没接,它们的方块实体落到本联动手里
 * 会被当成"不是炊具"。
 */
public enum Cookware {
    /** 炒锅:倒油 → 下料 → 翻炒 → 出锅,出锅慢了会糊。 */
    POT("pot"),
    /** 汤锅:汤底 → 下料 → 盖盖慢炖 → 揭盖盛出,不会糊。 */
    STOCKPOT("stockpot");

    private final String id;

    Cookware(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 工具参数里的词 → 种类;认不出返回 null。 */
    public static Cookware byId(String id) {
        for (Cookware c : values()) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }
}
