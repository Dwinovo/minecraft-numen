package com.dwinovo.numen.api.agent.catalog;

/**
 * 统一的思考档位。{@link #AUTO} 什么思考参数都不发,把决定权留给服务端;其余档位由
 * {@link ThinkingTable} 翻成这个型号认的写法。
 */
public enum ThinkingLevel {
    AUTO("auto"), OFF("off"), LOW("low"), MEDIUM("medium"), HIGH("high"), MAX("max");

    private final String id;

    ThinkingLevel(String id) {
        this.id = id;
    }

    /** 存档里的写法。 */
    public String id() {
        return id;
    }

    /** 按存档写法找档位;空或不认识都是 {@link #AUTO}(档案里没选过)。 */
    public static ThinkingLevel byId(String id) {
        if (id != null) {
            for (ThinkingLevel l : values()) {
                if (l.id.equals(id.strip())) return l;
            }
        }
        return AUTO;
    }
}
