package com.dwinovo.numen.permission;

/**
 * 裁决的三种答复:放行;拒绝并附理由;需要主人同意并附理由。
 *
 * <p>征询尚未接入,{@link Kind#ASK} 由调用方按拒绝处理——{@link #allowed} 对它也是 false,
 * {@link #reason} 里写明是等主人点头而不是不许。调用方只读 {@link #allowed} 与 {@link #reason};
 * 规划器另看 {@link #asks} 给需要同意的格子算有限代价;回执要按原因归堆时读 {@link #cause}。
 *
 * @param kind  答复
 * @param cause 命中的那条规则或那个模式的自述,如 {@code placed by a player};放行为空串
 */
public record Verdict(Kind kind, String cause) {

    public enum Kind { ALLOW, DENY, ASK }

    /** 需要同意的裁决在征询接入前统一带的话。 */
    public static final String CONSENT_NOT_WIRED = "needs the owner's consent (asking is not wired up yet)";

    private static final Verdict ALLOW = new Verdict(Kind.ALLOW, "");

    public static Verdict allow() {
        return ALLOW;
    }

    public static Verdict deny(String cause) {
        return new Verdict(Kind.DENY, cause);
    }

    public static Verdict ask(String cause) {
        return new Verdict(Kind.ASK, cause);
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
            case ASK -> cause + ": " + CONSENT_NOT_WIRED;
        };
    }
}
