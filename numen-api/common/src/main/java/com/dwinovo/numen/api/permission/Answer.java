package com.dwinovo.numen.api.permission;

import com.dwinovo.numen.permission.Say;

import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * 一个回答者对一件事的回答:不许、要问、许、不表态。不许与要问带一句理由,两种说法——{@code reason} 是英文一句,给模型与日志
 * (回执里拒绝与要问的理由,不带主语);{@code shown} 是同一句给主人看的,征询卡上显示,用回答者自己语言文件里的翻译键。
 *
 * @param say    回答
 * @param reason 英文理由;许与不表态为空串
 * @param shown  给主人看的理由;许与不表态为空
 */
public record Answer(Say say, String reason, Component shown) {

    /** 这事我不懂。 */
    public static final Answer ABSTAIN = new Answer(Say.ABSTAIN, "", Component.empty());
    /** 许。 */
    public static final Answer ALLOW = new Answer(Say.ALLOW, "", Component.empty());

    public Answer {
        Objects.requireNonNull(say, "say");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(shown, "shown");
    }

    /** 不许,解不开。 */
    public static Answer deny(String reason, Component shown) {
        return new Answer(Say.DENY, reason, shown);
    }

    /** 要问主人。 */
    public static Answer ask(String reason, Component shown) {
        return new Answer(Say.ASK, reason, shown);
    }
}
