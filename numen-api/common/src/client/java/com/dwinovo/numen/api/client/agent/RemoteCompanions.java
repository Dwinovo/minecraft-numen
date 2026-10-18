package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.History;
import com.dwinovo.numen.api.brain.Libraries;
import com.dwinovo.numen.api.brain.OwnerInput;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.FeedPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HelloPayload;
import com.dwinovo.numen.api.network.payload.HistoryPagePayload;
import com.dwinovo.numen.api.network.payload.HistoryRequestPayload;
import com.dwinovo.numen.api.network.payload.OwnerInputPayload;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 主人客户端上所有服务端大脑的视图({@link RemoteCompanion}),以及它们共用的东西:往服务端发话、会话记录({@link RemoteTranscripts})的
 * 翻页、服务器报到的结果。服务端推来的包经这里分给各只同伴。内存里的视图,断线就丢,不存任何东西。
 *
 * <p>线程:客户端主线程(包的处理在主线程上)。
 */
public final class RemoteCompanions {

    private record Entry(RemoteCompanion view, TurnPresenter presenter) {}

    private static final Map<UUID, Entry> COMPANIONS = new HashMap<>();
    private static final RemoteTranscripts TRANSCRIPTS = new RemoteTranscripts();
    /** 服务端回答过报到、版本对得上:它推的东西我们读得懂。 */
    private static boolean served;
    /** 这位玩家能不能改服务器上的配置。 */
    private static boolean admin;
    /** 服务端发来的落过盘的会话(成员表与名字),整份。 */
    private static List<Conversation> saved = List.of();
    private static long nextRequest;
    private static final RemoteLibrary LIBRARY = new RemoteLibrary(NumenNetwork::sendToServer);
    /** 这个连接上报过到了。 */
    private static boolean helloSent;

    private RemoteCompanions() {}

    // ---- 取 ----

    public static Optional<RemoteCompanion> get(UUID companion) {
        Entry entry = COMPANIONS.get(companion);
        return entry == null ? Optional.empty() : Optional.of(entry.view());
    }

    /** 这只同伴的视图,没有就建一个:它只是个把手,她的东西什么时候推来什么时候有。 */
    public static RemoteCompanion getOrCreate(UUID companion) {
        return COMPANIONS.computeIfAbsent(companion, RemoteCompanions::open).view();
    }

    private static Entry open(UUID companion) {
        RemoteCompanion[] self = new RemoteCompanion[1];
        TurnPresenter presenter = new TurnPresenter(companion, () -> self[0].status(), () -> self[0].personaName(),
                playing -> send(new OwnerInput.Speaking(companion, playing)));
        self[0] = new RemoteCompanion(companion, RemoteCompanions::send, feed -> {
            if (feed instanceof com.dwinovo.numen.api.brain.Feed.Wiped) {
                TRANSCRIPTS.clear();   // 她的日志没了;群里别的会话里她说过的也在其中,都重新取
            }
            presenter.on(feed);
        },
                line -> TRANSCRIPTS.live(line.conv() == null ? companion.toString() : line.conv(), companion, line));
        return new Entry(self[0], presenter);
    }

    /** 她不在了(遣散):放掉这头的视图。她的东西都在服务端,那边收。 */
    public static void forget(UUID companion) {
        COMPANIONS.remove(companion);
    }

    /** 每个客户端刻一次:各视图的表现层往前走一步(打字机、语音、说话状态)。 */
    public static void tick() {
        // 循环属于一次连接:标题画面上没有连接,身体够不着、包发不出去
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            return;
        }
        for (Entry entry : COMPANIONS.values()) {
            entry.presenter().tick();
        }
    }

    /** 服务端是否认得我们、我们读得懂它。 */
    public static boolean served() {
        return served;
    }

    public static boolean admin() {
        return admin;
    }

    /** 服务器上的库(人设、模型档案、服务器配置)在这里读写。 */
    public static RemoteLibrary library() {
        return LIBRARY;
    }

    public static List<Conversation> savedConversations() {
        return saved;
    }

    // ---- 发 ----

    /** 主人做的一件事,发给服务端;能不能办、办不办由它判。 */
    public static void send(OwnerInput input) {
        NumenNetwork.sendToServer(OwnerInputPayload.of(input));
    }

    /** 服务端推来的包挂进主源码集的挂点(客户端入口启动时调一次)。 */
    public static void install() {
        ClientPayloadSink.helloAck = RemoteCompanions::onHelloAck;
        ClientPayloadSink.feed = RemoteCompanions::onFeed;
        ClientPayloadSink.historyPage = RemoteCompanions::onHistoryPage;
        ClientPayloadSink.conversations = RemoteCompanions::onConversations;
        ClientPayloadSink.export = MemoryFiles::receive;
        ClientPayloadSink.library = p -> {
            LIBRARY.onPayload(p);
            if (Libraries.MCP_CLIENT.equals(p.kind())) {
                McpAuthPrompt.onServers();
            }
            if (Libraries.KEY.equals(p.kind()) && ServerLibraries.keyNotice() != null) {
                com.dwinovo.numen.api.client.hud.NumenHudToasts.push(
                        com.dwinovo.numen.api.client.ui.NumenToasts.Severity.WARN,
                        net.minecraft.client.resources.language.I18n.get(
                                com.dwinovo.numen.api.data.ModLanguageData.Keys.KEY_OWNER_SEES));
            }
        };
    }

    /**
     * 进服后报到:告诉服务端我们装着客户端、说哪一版协议。每个连接一次——服务端在名册推来时就知道这个连接已经就绪,
     * 所以接在名册到达上。
     */
    public static void hello() {
        if (!helloSent) {
            helloSent = true;
            NumenNetwork.sendToServer(new HelloPayload(Presence.PROTOCOL));
        }
    }

    // ---- 会话记录 ----

    /** 面板正在看的这个会话的记录,从老到新。 */
    public static List<Transcript.Entry> transcript(String conversation) {
        if (TRANSCRIPTS.wantsFirstPage(conversation)) {
            requestPage(conversation);
        }
        return TRANSCRIPTS.entries(conversation);
    }

    /** 往上翻到头了:要更早的一页(已经在取、或再没有更早的就不重复要)。 */
    public static void loadOlder(String conversation) {
        if (TRANSCRIPTS.wantsOlder(conversation)) {
            requestPage(conversation);
        }
    }

    private static void requestPage(String conversation) {
        TRANSCRIPTS.asked(conversation);
        NumenNetwork.sendToServer(new HistoryRequestPayload(nextRequest++, conversation,
                TRANSCRIPTS.cursor(conversation), History.PAGE));
    }

    // ---- 服务端推来的 ----

    static void onHelloAck(HelloAckPayload p) {
        served = p.protocol() == Presence.PROTOCOL;
        admin = served && p.admin();
        if (!served) {
            Constants.LOG.warn("[numen-net] the server speaks protocol {}, this client {}: its companions are not shown",
                    p.protocol(), Presence.PROTOCOL);
            return;
        }
        // 召唤卡、设置页要用的这几张表先要来;页面打开时再要一次拿最新的
        for (String kind : List.of(Libraries.PERSONA, Libraries.PROVIDER, Libraries.PREFS, Libraries.SKILL, Libraries.NOTES,
                Libraries.MCP_CLIENT, Libraries.MCP_SERVER)) {
            LIBRARY.ask(kind);
        }
    }

    static void onFeed(FeedPayload p) {
        List<Feed> events;
        try {
            events = p.events();
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-net] dropped an unreadable feed for {}: {}", p.companion(), unreadable.toString());
            return;
        }
        getOrCreate(p.companion()).apply(events);
    }

    static void onHistoryPage(HistoryPagePayload p) {
        List<Transcript.Entry> entries;
        try {
            entries = p.entries();
        } catch (RuntimeException unreadable) {
            Constants.LOG.warn("[numen-net] dropped an unreadable history page: {}", unreadable.toString());
            return;
        }
        TRANSCRIPTS.page(p.conversation(), entries, p.cursor(), p.more());
    }

    static void onConversations(ConversationsPayload p) {
        saved = List.copyOf(p.conversations());
    }

    /** 断线:服务端推来的东西都不算数了。 */
    public static void disconnected() {
        COMPANIONS.clear();
        TRANSCRIPTS.clear();
        MemoryFiles.reset();
        saved = List.of();
        LIBRARY.clear();
        McpAuthPrompt.clear();
        served = false;
        admin = false;
        helloSent = false;
    }
}
