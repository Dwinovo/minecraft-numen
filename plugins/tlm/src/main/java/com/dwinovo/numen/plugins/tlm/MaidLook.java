package com.dwinovo.numen.plugins.tlm;

import com.dwinovo.numen.api.entity.NumenPlayer;

/**
 * 让同伴<b>随时</b>知道自己现在穿着谁。
 *
 * <h2>为什么不能只靠工具返回值</h2>
 * {@code tlm wear} 的结果只在换装那一轮待在上下文里。下一轮她还记得,
 * 整理过记忆、或者重进游戏之后就不记得了——于是"你现在是管理员小企鹅"这件事
 * 会悄悄消失,她照旧用原来的调子说话。
 *
 * <p>挂在 {@code NumenApi.contributeBodyState} 上的东西每次请求现算、每轮都在,
 * 在服务端从身体上读她穿的模型(不看她在不在主人的视野里),而且一个字不进会话历史,所以换多少次都不会把上下文撑起来。
 */
public final class MaidLook {

    private MaidLook() {}

    /** 交给 Numen API 的现算片段;没穿女仆模型就什么也不说。 */
    public static String describe(NumenPlayer her) {
        String id = Outfit.worn(her);
        if (id == null) return "";

        String name = MaidCatalog.nameOf(id);
        String desc = MaidCatalog.descOf(id);

        StringBuilder sb = new StringBuilder("<maid_look>You currently look like \"").append(name).append("\"");
        if (!desc.isEmpty()) sb.append(", described as: ").append(desc);
        sb.append(". You may let a little of this character into how you talk and act, but you are still yourself; do not swap your whole persona for it.");
        // 这一句是必须的:女仆模型接管了整个身体渲染,别的外观(YSM 之类)在它底下
        // 一点都露不出来。不说的话,她换了别的外观会照样回报"换好了",而主人画面上
        // 什么都没发生——命令确实成功了,只是被盖着。
        sb.append("This model covers your whole body, so any other look (a YSM model, say) is hidden under it; "
                + "to show another look, take this one off first with tlm.skin.remove().");

        return sb.append("</maid_look>").toString();
    }
}
