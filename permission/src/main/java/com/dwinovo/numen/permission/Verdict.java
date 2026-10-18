package com.dwinovo.numen.permission;

/**
 * 裁决的三种答复:放行;拒绝并附理由;需要主人同意并附理由。
 *
 * <p>{@link Kind#ASK} 不是放行——{@link #allowed} 对它是 false。征询只在执行开始时由任务发起;主人答应之后,
 * 那几件事作为任务期授权进了裁决,再问就是放行。调用方读 {@link #allowed} 与 {@link #reason};清单与回执要按
 * 原因归堆时读 {@link #cause}。
 *
 * @param kind  答复
 * @param cause 命中的那条规则、那个模式的自述,如 {@code belongs to the owner};放行为空串
 * @param rule  定下这个答复的那一行规则(allow、ask、deny 行与硬规则);模式、任务期授权与"没有任何一行覆盖"为 null
 * @param layer {@code rule} 在哪一层:{@link #HARD}、{@link Policy} 里的第几层;没有规则为 {@link #NO_LAYER}
 */
public record Verdict(Kind kind, String cause, Rule rule, int layer) {

    public enum Kind { ALLOW, DENY, ASK }

    /** 没有任何一行规则覆盖这个动作时的自述。 */
    public static final String UNCOVERED = "no rule covers this action";

    /** 定下答复的是硬规则。 */
    public static final int HARD = -1;
    /** 答复不是哪一行规则定的(模式、任务期授权、没有规则覆盖)。 */
    public static final int NO_LAYER = -2;

    private static final Verdict ALLOW = new Verdict(Kind.ALLOW, "", null, NO_LAYER);
    private static final Verdict ASK_UNCOVERED = new Verdict(Kind.ASK, UNCOVERED, null, NO_LAYER);

    /** 放行,不是哪一行规则定的:bypass 与任务期授权。 */
    public static Verdict allow() {
        return ALLOW;
    }

    /** 一行 allow 规则放行。 */
    public static Verdict allow(Rule by, int layer) {
        return new Verdict(Kind.ALLOW, "", by, layer);
    }

    /** 不是哪一行规则定的拒绝:observe 模式。 */
    public static Verdict deny(String cause) {
        return new Verdict(Kind.DENY, cause, null, NO_LAYER);
    }

    /** 一行 deny 规则或硬规则拒绝。 */
    public static Verdict deny(String cause, Rule by, int layer) {
        return new Verdict(Kind.DENY, cause, by, layer);
    }

    /** 命中一行 ask 规则;{@code cause} 是这行规则就这个动作的自述。 */
    public static Verdict ask(Rule hit, String cause, int layer) {
        return new Verdict(Kind.ASK, cause, hit, layer);
    }

    /** 哪一行规则都没说到:问,不放行。 */
    public static Verdict uncovered() {
        return ASK_UNCOVERED;
    }

    public boolean allowed() {
        return kind == Kind.ALLOW;
    }

    public boolean asks() {
        return kind == Kind.ASK;
    }

    /** 给回执的整句理由;放行为空串。 */
    public String reason() {
        return switch (kind) {
            case ALLOW -> "";
            case DENY -> cause;
            case ASK -> cause + ": needs the owner's consent";
        };
    }
}
