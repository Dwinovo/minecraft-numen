package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import net.minecraft.client.resources.language.I18n;

/**
 * "她此刻在干什么"({@link LoopStatus.Doing})翻成当前语言的话,界面里说这件事的唯一一处:头顶气泡、档案页都用它。
 * 键有译文就翻(任务的说法、{@code numen.tool.<工具名>}),没有译文的(接进来的外部工具)原样说工具名,即第一个词。
 */
public final class ActivityWords {

    private ActivityWords() {}

    public static String text(LoopStatus.Doing doing) {
        if (I18n.exists(doing.key())) {
            return I18n.get(doing.key(), doing.args().toArray());
        }
        return doing.args().isEmpty() ? doing.key() : doing.args().get(0);
    }
}
