package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.inbox.EventTypes;
import com.dwinovo.numen.api.entity.NumenPlayer;

import java.util.UUID;

/**
 * 原版 {@code /msg}(与 {@code /tell}、{@code /w})发给她的话。她是真玩家,私聊的包会到她的"客户端"({@code FakeClient}),
 * 在这里按谁发的分开:
 * <ul>
 *   <li><b>她的主人</b>:他的话进她的单聊会话,走和面板输入框同一个入口({@link OwnerInputs}),她私信回({@link Venue#WHISPER});</li>
 *   <li><b>别的玩家</b>:按群聊的旁听档({@link EventTypes#TALK},捎带投递)进她的收件箱,标着是谁说的——
 *       不叫醒她,不花主人的钱。</li>
 * </ul>
 * 不读公屏:公屏上提到或 {@code @} 她都不是跟她说话,这里只接私聊。
 *
 * <p>线程:服务端主线程(指令的执行里)。
 */
public final class Whispers {

    private Whispers() {}

    /**
     * @param companion  收到私聊的她
     * @param sender     发话的玩家
     * @param senderName 发话的人此刻显示的名字
     */
    public static void heard(NumenPlayer companion, UUID sender, String senderName, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        if (companion.isOwnedByPlayer(sender)) {
            OwnerInputs.handle(companion.server, sender, new OwnerInput.Say(companion.getUUID().toString(), text),
                    Venue.WHISPER);
            return;
        }
        CompanionLoops.get(companion.getUUID()).ifPresent(loop -> loop.submitEvent(EventTypes.TALK,
                Conversations.overheardLine(senderName + " → " + companion.getName().getString(), text)));
    }
}
