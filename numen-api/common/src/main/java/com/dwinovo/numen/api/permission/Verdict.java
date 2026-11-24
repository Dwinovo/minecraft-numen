package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Merge;
import com.dwinovo.numen.permission.Tier;

import net.minecraft.network.chat.Component;

/**
 * 一次裁决的结果:许、问、不许,以及为什么。{@link #asks} 不是放行——{@link #allowed} 对它是 false;征询由发起动作的一方去问。
 * 调用方读 {@link #allowed} 与 {@link #reason};清单与回执要按原因归堆时读 {@link #cause}。
 *
 * @param tier  结果
 * @param step  哪一步定下的({@link Merge})
 * @param cause 英文原因,不带主语:{@code belongs to the owner};许为空串
 * @param shown 同一句给主人看的,征询卡上显示;许为空
 */
public record Verdict(Tier tier, Merge.Step step, String cause, Component shown) {

    private static final Verdict ALLOW = new Verdict(Tier.ALLOW, Merge.Step.TIER, "", Component.empty());

    /** 许。 */
    public static Verdict allow() {
        return ALLOW;
    }

    /** 许,是 {@code step} 那一步定下的。 */
    public static Verdict allow(Merge.Step step) {
        return new Verdict(Tier.ALLOW, step, "", Component.empty());
    }

    /** 不是回答者与档位定下的拒绝(服务端把这一下退回来了)。 */
    public static Verdict deny(String cause) {
        return new Verdict(Tier.DENY, Merge.Step.TIER, cause, Component.literal(cause));
    }

    public boolean allowed() {
        return tier == Tier.ALLOW;
    }

    public boolean asks() {
        return tier == Tier.ASK;
    }

    /** 给回执的整句理由;许为空串。 */
    public String reason() {
        return switch (tier) {
            case ALLOW -> "";
            case DENY -> cause;
            case ASK -> cause + ": needs the owner's consent";
        };
    }
}
