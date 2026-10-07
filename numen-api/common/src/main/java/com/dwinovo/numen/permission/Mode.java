package com.dwinovo.numen.permission;

/**
 * 每个同伴一个,主人设。{@link #ASK} 走规则表;{@link #BYPASS} 全放行(单机不想被打扰的人用);
 * {@link #OBSERVE} 只看不动,拒绝一切改世界的动作,等于 plan mode。
 */
public enum Mode {
    ASK, BYPASS, OBSERVE;

    /** 存档里的名字(小写)。认不出返回 {@link #ASK}。 */
    public static Mode byName(String name) {
        for (Mode m : values()) {
            if (m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return ASK;
    }
}
