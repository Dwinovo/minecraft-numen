package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 她说出口的话在聊天里的样子——服务端<b>唯一的一处</b>({@code say} 经座位把话交来,这里写成聊天行)。
 */
public final class Voice {

    private Voice() {}

    /**
     * 她的一句话在主人的聊天栏里的样子。私信照原版收私信的样子(与原版客户端的语言键一致,谁的客户端都认得);
     * 会话里的话带会话名;面板问的而主人没有面板时,就是一行"&lt;她&gt; 话"。
     *
     * @param conversation 会话名;只有 {@link Venue#CHAT} 用
     */
    public static Component line(Venue venue, String name, String conversation, String said) {
        return switch (venue) {
            case WHISPER -> Component.translatable("commands.message.display.incoming", name, said)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            case CHAT -> Component.literal("[" + conversation + "] <" + name + "> " + said);
            case PANEL -> Component.literal("<" + name + "> " + said);
        };
    }
}
