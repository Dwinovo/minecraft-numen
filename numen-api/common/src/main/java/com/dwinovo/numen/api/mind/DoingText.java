package com.dwinovo.numen.api.mind;

import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.tool.ToolLine;
import net.minecraft.network.chat.Component;

import java.util.function.Predicate;

/**
 * "她此刻在干什么"({@link LoopStatus.Doing})说成话的唯一一处:主人的客户端上头顶气泡与档案页用它,没装客户端的主人的动作栏也用它。
 * 键有译文就翻(任务的说法、{@code numen.tool.<工具名>}),没有译文的(接进来的外部工具)原样说工具名,即第一个词;
 * 工具调用带着第二个词(工具说这次调用读作什么)时,后面按 {@link ToolLine} 接上。
 * "有没有译文"由调用方按自己那一头的语言表回答:客户端问资源包,服务端问 {@code ServerLang}。
 */
public final class DoingText {

    private DoingText() {}

    /** @param translated 这个键在说话的这一头有没有译文 */
    public static Component of(LoopStatus.Doing doing, Predicate<String> translated) {
        Component said = translated.test(doing.key())
                ? Component.translatable(doing.key(), doing.args().toArray())
                : Component.literal(doing.args().isEmpty() ? doing.key() : doing.args().get(0));
        // 工具调用的第二个词是这个工具说这次调用读作什么,和聊天里的工具条同一种读法
        return doing.key().startsWith(ToolLine.KEY_PREFIX) && doing.args().size() > 1
                ? ToolLine.of(said, doing.args().get(1)) : said;
    }
}
