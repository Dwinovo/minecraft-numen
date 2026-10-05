package com.dwinovo.numen.bench.report;

/**
 * 一个场景跑的三种样子。两种基线不花 API,每次先跑,证明场景可解、断言不被空操作骗过。
 */
public enum Variant {
    /** 标准解:按顺序直接执行场景作者写好的命令,必须 100% 通过。 */
    SOLUTION("solution"),
    /** 空操作:她只答一句、什么都不做,必须 0% 通过。 */
    NOOP("noop"),
    /** 真实模型。 */
    LIVE("live");

    private final String id;

    Variant(String id) {
        this.id = id;
    }

    /** 写进记录的名字。 */
    public String id() {
        return id;
    }

    public static Variant of(String id) {
        for (Variant v : values()) {
            if (v.id.equals(id)) {
                return v;
            }
        }
        throw new IllegalArgumentException("unknown variant: " + id);
    }
}
