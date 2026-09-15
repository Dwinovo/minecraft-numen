package com.dwinovo.numen.permission;

import com.dwinovo.numen.data.ModLanguageData;

import net.minecraft.network.chat.Component;

/**
 * 裁决的三种答复:放行;拒绝并附理由;需要主人同意并附理由。
 *
 * <p>{@link Kind#ASK} 在落点上不是放行——{@link #allowed} 对它是 false。征询只在执行开始时由
 * 任务发起({@link ConsentDesk});主人答应之后,那几件事作为任务期授权进了裁决快照,再问就是放行。
 * 调用方读 {@link #allowed} 与 {@link #reason};规划器另看 {@link #asks} 给需要同意的格子算有限
 * 代价;清单与回执要按原因归堆时读 {@link #cause}。
 *
 * @param kind  答复
 * @param cause 命中的那条规则、那个模式或那个外部强制的自述,如 {@code placed by a player};放行为空串
 * @param rule  问的是哪一行 ask 规则;没有任何一行覆盖、以及放行与拒绝时为 null
 */
public record Verdict(Kind kind, String cause, Rule rule) {

    public enum Kind { ALLOW, DENY, ASK }

    /** 没有任何一行规则覆盖这个动作时的自述。 */
    public static final String UNCOVERED = "no rule covers this action";

    private static final Verdict ALLOW = new Verdict(Kind.ALLOW, "", null);
    private static final Verdict ASK_UNCOVERED = new Verdict(Kind.ASK, UNCOVERED, null);

    public static Verdict allow() {
        return ALLOW;
    }

    public static Verdict deny(String cause) {
        return new Verdict(Kind.DENY, cause, null);
    }

    /** 命中一行 ask 规则。 */
    public static Verdict ask(Rule hit) {
        return new Verdict(Kind.ASK, hit.describe(), hit);
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

    /** 要问的理由给主人看的那一版({@link #cause} 的对应):命中的那行规则,或者哪一行都没说到。 */
    public Component shownCause() {
        return rule != null ? rule.shown() : Component.translatable(ModLanguageData.Keys.PERMISSION_UNCOVERED);
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
