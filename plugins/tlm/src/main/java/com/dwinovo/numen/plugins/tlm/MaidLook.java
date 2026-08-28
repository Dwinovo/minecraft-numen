package com.dwinovo.numen.plugins.tlm;

import java.util.UUID;

/**
 * 让同伴<b>随时</b>知道自己现在穿着谁。
 *
 * <h2>为什么不能只靠工具返回值</h2>
 * {@code wear_maid_model} 的结果只在换装那一轮待在上下文里。下一轮她还记得,
 * 整理过记忆、或者重进游戏之后就不记得了——于是"你现在是管理员小企鹅"这件事
 * 会悄悄消失,她照旧用原来的调子说话。
 *
 * <p>挂在 {@code NumenApi.contributeState} 上的东西每次请求现算、每轮都在,
 * 而且一个字不进会话历史,所以换多少次都不会把上下文撑起来。
 */
public final class MaidLook {

    private MaidLook() {}

    /** 交给引擎的现算片段;没穿女仆模型就什么也不说。 */
    public static String describe(UUID companion) {
        String id = Wardrobe.worn(companion);
        if (id == null || !Tlm.exists(id)) return "";

        String name = MaidCatalog.nameOf(id);
        String desc = MaidCatalog.descOf(id);

        StringBuilder sb = new StringBuilder("<maid_look>你现在的样子是「").append(name).append("」");
        if (!desc.isEmpty()) sb.append(",介绍是:").append(desc);
        sb.append("。说话行事可以带点这个角色的味道,但你还是你自己,别把人设整个换掉。");
        // 这一句是必须的:女仆模型接管了整个身体渲染,别的外观(YSM 之类)在它底下
        // 一点都露不出来。不说的话,她换了别的外观会照样回报"换好了",而主人画面上
        // 什么都没发生——命令确实成功了,只是被盖着。
        sb.append("这套模型盖住了你的整个身体,别的外观(比如 YSM 的模型)在它底下看不见;"
                + "要露出别的外观,得先用 wear_maid_model 把它脱下来(model 留空)。");

        return sb.append("</maid_look>").toString();
    }
}
