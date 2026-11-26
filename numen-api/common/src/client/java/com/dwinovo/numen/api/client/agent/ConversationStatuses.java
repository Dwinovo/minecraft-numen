package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.client.consent.ConsentCards;
import com.dwinovo.numen.api.client.screen.chat.ConversationPreview;

import java.util.UUID;

/** 从游戏里的现状收集一个会话的事实,交给 {@link ConversationStatus#of} 算。 */
public final class ConversationStatuses {

    private ConversationStatuses() {}

    /**
     * @param viewing 主人此刻正对着这个会话:正看着的话没有"没看过"
     */
    public static ConversationStatus of(Conversation c, boolean viewing) {
        Conversations convos = Conversations.instance();
        UUID her = convos.soloOf(c);
        boolean unseen = !viewing && ConversationPreview.unseen(c, convos.lastSeen(c));
        if (her == null) {
            return ConversationStatus.of(new ConversationStatus.Facts(true, true, false, false, unseen, false));
        }
        LoopStatus loop = ClientCompanions.get(her).map(CompanionView::status).orElse(null);
        boolean waiting = ConsentCards.pending(her) != null || Conversations.asking(c) != null;
        boolean error = loop != null && (loop.hold() == Hold.BLOCKED || loop.hold() == Hold.FAILED);
        return ConversationStatus.of(new ConversationStatus.Facts(false, online(her), waiting, error, unseen,
                loop != null && loop.busy()));
    }

    /** 在世界里:没被留在外面,也没被请出服务器。 */
    public static boolean online(UUID her) {
        return !NumenRoster.instance().outside(her).isOutside();
    }
}
