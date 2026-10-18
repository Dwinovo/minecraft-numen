package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.brain.DoingText;
import net.minecraft.client.resources.language.I18n;

/**
 * "她此刻在干什么"({@link LoopStatus.Doing})翻成当前语言的话,界面里说这件事的唯一一处:头顶气泡、档案页都用它。
 * 怎么说是 {@link DoingText} 的规矩(服务端给没装客户端的主人的动作栏用的是同一条),这里只回答客户端这一头"哪些键有译文"。
 */
public final class ActivityWords {

    private ActivityWords() {}

    public static String text(LoopStatus.Doing doing) {
        return DoingText.of(doing, I18n::exists).getString();
    }
}
