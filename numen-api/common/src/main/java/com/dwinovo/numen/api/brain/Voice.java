package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.loop.LoopEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * 她说出口了——服务端<b>唯一的一处</b>。循环里她的一条回复落了库(含开工前顺嘴的一句),只要有话:
 * <ul>
 *   <li>同会话的其他成员旁听到一份({@link Conversations#heard});</li>
 *   <li>主人那一头按他是在哪儿问的({@link Venue})接话({@link CompanionLoops.Link#said}):面板里问的,装了客户端的
 *       随 {@link Feed.Replied} 在面板里看到,没装的得到一行聊天;命令切进会话问的,回在带 {@code [会话名]} 的聊天行;
 *       {@code /msg} 问的,回私信。</li>
 *   <li>渠道来访着的(主人从 QQ 等渠道说的话,见 {@link Visits}),话回给那个渠道。</li>
 * </ul>
 * 外部大脑替她说的话以后也走这一处。
 */
final class Voice {

    private Voice() {}

    static void attach(CompanionLoop loop, Conversations rooms, CompanionLoops.Link link) {
        loop.subscribe(event -> {
            if (event instanceof LoopEvent.AssistantMessage message) {
                String said = message.turn().content() == null ? "" : message.turn().content().strip();
                if (said.isEmpty()) {
                    return;
                }
                rooms.heard(loop.companion(), said);
                Visits.said(loop.companion(), said);
                link.said(loop.companion(), said, loop.venue(),
                        rooms.titleFor(loop.companion(), loop.conversation()));
            }
        });
    }

    /**
     * 她的一句话在主人的聊天栏里的样子。私信照原版收私信的样子(与原版客户端的语言键一致,谁的客户端都认得);
     * 会话里的话带会话名;面板问的而主人没有面板时,就是一行"&lt;她&gt; 话"。
     *
     * @param conversation 会话名;只有 {@link Venue#CHAT} 用
     */
    static Component line(Venue venue, String name, String conversation, String said) {
        return switch (venue) {
            case WHISPER -> Component.translatable("commands.message.display.incoming", name, said)
                    .withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC);
            case CHAT -> Component.literal("[" + conversation + "] <" + name + "> " + said);
            case PANEL -> Component.literal("<" + name + "> " + said);
        };
    }
}
