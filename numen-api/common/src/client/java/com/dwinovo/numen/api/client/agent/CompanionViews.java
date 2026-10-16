package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.agent.llm.ConvoLog;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 界面拿同伴视图({@link CompanionView})和会话记录的唯一入口。她的大脑在哪({@link BrainSite#CURRENT})只在这一处判:
 * 在客户端是旧的循环,在服务端是 {@link RemoteCompanions} 里服务端推来的视图。界面其余地方不知道这件事。
 */
public final class CompanionViews {

    private CompanionViews() {}

    /** 这只同伴的视图;还没有是空。 */
    public static Optional<CompanionView> get(UUID companion) {
        return BrainSite.CURRENT == BrainSite.SERVER
                ? RemoteCompanions.get(companion).map(view -> view)
                : AgentLoopRegistry.get(companion).map(loop -> loop);
    }

    /** 这只同伴的视图,没有就建。 */
    public static CompanionView getOrCreate(UUID companion) {
        return BrainSite.CURRENT == BrainSite.SERVER
                ? RemoteCompanions.getOrCreate(companion)
                : AgentLoopRegistry.getOrCreate(companion);
    }

    /**
     * 这个会话的记录,从老到新:成员各自的日志里盖着它印的行,按时间归并——单成员时就是她一本日志,同一条路。
     * 在服务端,归并和翻页都在那头做,这里只是读到手的那些。
     *
     * @param tag     会话印;{@code null} = 就他俩
     * @param members 会话里还在的成员
     */
    public static List<Transcript.Entry> transcript(Conversation conversation, String tag, List<UUID> members) {
        if (BrainSite.CURRENT == BrainSite.SERVER) {
            return RemoteCompanions.transcript(conversation.id());
        }
        Map<UUID, List<ConvoLog.Line>> logs = new LinkedHashMap<>();
        for (UUID member : members) {
            AgentLoopRegistry.get(member).ifPresent(loop -> logs.put(member, loop.display()));
        }
        return Transcript.merge(tag, logs);
    }

    /** 往上翻到头了:要更早的记录。旧的循环把整份都读进了内存,没有更早的可要。 */
    public static void loadOlder(Conversation conversation) {
        if (BrainSite.CURRENT == BrainSite.SERVER) {
            RemoteCompanions.loadOlder(conversation.id());
        }
    }
}
