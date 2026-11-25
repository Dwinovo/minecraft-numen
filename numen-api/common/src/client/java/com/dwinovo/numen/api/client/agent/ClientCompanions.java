package com.dwinovo.numen.api.client.agent;

import com.dwinovo.numen.api.Constants;
import com.dwinovo.numen.api.agent.conversation.Conversation;
import com.dwinovo.numen.api.agent.conversation.Transcript;
import com.dwinovo.numen.api.mind.Feed;
import com.dwinovo.numen.api.mind.FeedEncoder;
import com.dwinovo.numen.api.owner.OwnerInput;
import com.dwinovo.numen.api.owner.Presence;
import com.dwinovo.numen.api.client.seat.ClientSeats;
import com.dwinovo.numen.api.library.Library;
import com.dwinovo.numen.api.library.LibraryKind;
import com.dwinovo.numen.api.client.ClientMachines;
import com.dwinovo.numen.api.mind.ClientServices;
import com.dwinovo.numen.api.mind.History;
import com.dwinovo.numen.api.mind.LibraryDesk;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.network.ClientPayloadSink;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.payload.ClientCapsPayload;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HelloPayload;
import com.dwinovo.numen.api.network.payload.LibraryPayload;
import com.dwinovo.numen.api.network.payload.LibraryRequestPayload;
import com.dwinovo.numen.api.network.payload.OwnerInputPayload;
import com.dwinovo.numen.api.network.payload.ServerHelloPayload;
import com.dwinovo.numen.api.plugin.ClientMods;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 主人客户端上她们的大脑在界面这头的样子:每只同伴一个视图({@link CompanionView}),和它们共用的东西:往服务端发那几样服务端管的事(分享、
 * 会话的成员表)、配置库的表({@link LibraryTables})、会话记录({@link Transcripts})的翻页、服务器报到的结果。大脑本身在
 * {@link ClientServices} 的 {@code ClientBrains} 里,一个连接一份;这里只是界面的把手,断线就丢。
 *
 * <p>线程:客户端主线程(包的处理在主线程上)。
 */
public final class ClientCompanions {

    private record Entry(CompanionView view, TurnPresenter presenter, FeedEncoder encoder) {}

    /** 随库协议到服务端的几种库:服务端管的皮肤、权限、插件;别的库在这台电脑上,由 {@link LibraryDesk} 答。 */
    private static final Set<String> SERVER_KINDS = Set.of(com.dwinovo.numen.api.owner.Libraries.SKIN,
            com.dwinovo.numen.api.owner.Libraries.SKIN_FILES, com.dwinovo.numen.api.owner.Libraries.PERMISSION,
            com.dwinovo.numen.api.owner.Libraries.PLUGIN);

    private static final Map<UUID, Entry> COMPANIONS = new HashMap<>();
    private static final Transcripts TRANSCRIPTS = new Transcripts();
    /** 服务端收下了我们的报到(版本对得上):它推的东西我们读得懂。 */
    private static boolean served;
    /** 服务端发来的落过盘的会话(成员表与名字),整份。 */
    private static List<Conversation> saved = List.of();
    private static final LibraryTables LIBRARY = new LibraryTables(ClientCompanions::route);

    private ClientCompanions() {}

    // ---- 取 ----

    public static Optional<CompanionView> get(UUID companion) {
        Entry entry = COMPANIONS.get(companion);
        return entry == null ? Optional.empty() : Optional.of(entry.view());
    }

    /**
     * 这只同伴的视图,没有就建一个:她的大脑在这台电脑上({@link ClientSeats#brain})就接上它,不在(分享来的,脑子在她的创建者那台)
     * 就是一个没有大脑的视图,只能看。
     */
    public static CompanionView getOrCreate(UUID companion) {
        return COMPANIONS.computeIfAbsent(companion, ClientCompanions::open).view();
    }

    private static Entry open(UUID companion) {
        LoopHost brain = ClientSeats.brain(companion);
        CompanionView[] self = new CompanionView[1];
        TurnPresenter presenter = new TurnPresenter(companion, () -> self[0].status(), () -> self[0].personaName(),
                playing -> NumenNetwork.sendToServer(
                        new com.dwinovo.numen.api.network.payload.SpeakingStatePayload(companion, playing)));
        self[0] = new CompanionView(companion, brain, feed -> {
            if (feed instanceof Feed.Wiped) {
                TRANSCRIPTS.clear();   // 她的日志没了;群里别的会话里她说过的也在其中,都重新取
            }
            presenter.on(feed);
        }, line -> TRANSCRIPTS.live(line.conv() == null ? companion.toString() : line.conv(), companion, line));
        if (brain != null) {
            brain.questions().onAsked(presenter::asked);
        }
        FeedEncoder encoder = brain == null ? null : new FeedEncoder(brain, events -> self[0].apply(events));
        return new Entry(self[0], presenter, encoder);
    }

    /** 她不在了(遣散):放掉这头的视图。她的家由大脑那头收。 */
    public static void forget(UUID companion) {
        COMPANIONS.remove(companion);
    }

    /** 每个客户端刻一次:各只的大脑往前走一步,发生的事交给视图,表现层往前走一步(打字机、语音、说话状态)。 */
    public static void tick() {
        // 循环属于一次连接:标题画面上没有连接,身体够不着、包发不出去
        if (net.minecraft.client.Minecraft.getInstance().getConnection() == null) {
            return;
        }
        ClientSeats.tick();
        for (Entry entry : COMPANIONS.values()) {
            if (entry.encoder() != null) {
                entry.encoder().flush();
            }
            entry.presenter().tick();
        }
    }

    /** 服务端是否认得我们、我们读得懂它。 */
    public static boolean served() {
        return served;
    }

    /**
     * 这台客户端的玩家(服务端认的那个 UUID):她的创建者是不是我、这项绑定是不是我绑的,都拿它比。没在世界里是 {@code null}
     * (客户端还没起来时也是:这时谁都不是"我")。
     */
    public static UUID viewer() {
        var client = net.minecraft.client.Minecraft.getInstance();
        return client == null || client.player == null ? null : client.player.getUUID();
    }

    /** 配置库(人设、模型档案、钥匙串……在这台电脑上;皮肤、权限、插件在服务器上)的表在这里读写。 */
    public static LibraryTables library() {
        return LIBRARY;
    }

    /** 主人这台电脑上的一种库。 */
    public static Library library(LibraryKind kind) {
        return ClientMachines.get().library(kind);
    }

    public static List<Conversation> savedConversations() {
        return saved;
    }

    // ---- 发 ----

    /** 主人做的一件服务端管的事(分享、会话的成员表),发给服务端;能不能办、办不办由它判。 */
    public static void send(OwnerInput input) {
        NumenNetwork.sendToServer(OwnerInputPayload.of(input));
    }

    /** 一次配置库的读写:服务端管的库发给服务端,别的在这台电脑上办,答复同样交回表里。 */
    private static void route(LibraryRequestPayload request) {
        if (SERVER_KINDS.contains(request.kind())) {
            NumenNetwork.sendToServer(request);
            return;
        }
        LibraryDesk.Result result = ClientMachines.get().desk().handle(viewer(), request.kind(), request.op(),
                com.google.gson.JsonParser.parseString(request.json()).getAsJsonObject());
        long answerNow = result.later() == null ? request.request() : 0;
        LIBRARY.onPayload(new LibraryPayload(request.kind(), result.entries(),
                result.problem() == null ? "" : result.problem(), answerNow));
        if (result.later() != null) {
            result.later().thenAccept(entries -> net.minecraft.client.Minecraft.getInstance().execute(
                    () -> LIBRARY.onPayload(new LibraryPayload(request.kind(), entries, "", request.request()))));
        }
    }

    /** 服务端推来的包挂进主源码集的挂点(客户端入口启动时调一次)。 */
    public static void install() {
        ClientPayloadSink.serverHello = ClientCompanions::onServerHello;
        ClientPayloadSink.helloAck = ClientCompanions::onHelloAck;
        ClientPayloadSink.conversations = ClientCompanions::onConversations;
        ClientPayloadSink.library = LIBRARY::onPayload;
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

    /** 这个会话的成员里大脑在这台电脑上的,从各自的日志读一页。 */
    private static void requestPage(String conversation) {
        TRANSCRIPTS.asked(conversation);
        Conversations.Members members = Conversations.membersOf(conversation);
        History.page(members.brains(), members.tag(), TRANSCRIPTS.cursor(conversation), History.PAGE)
                .whenComplete((page, failed) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
                    if (failed != null) {
                        Constants.LOG.warn("[numen-mind] could not read a page of {}: {}", conversation, failed.toString());
                    } else {
                        TRANSCRIPTS.page(conversation, page.entries(), page.cursor(), page.more());
                    }
                }));
    }

    // ---- 服务端推来的 ----

    /** 服务端开场,说它的协议版本:对得上就报到,对不上它不会再发别的,我们也不发。 */
    static void onServerHello(ServerHelloPayload p) {
        if (p.protocol() != Presence.PROTOCOL) {
            Constants.LOG.warn("[numen-net] the server speaks protocol {}, this client {}: its companions are not shown",
                    p.protocol(), Presence.PROTOCOL);
            return;
        }
        NumenNetwork.sendToServer(new HelloPayload(Presence.PROTOCOL));
        // 紧跟着报这台电脑提供哪些目标模组只在客户端的联动:两包一起到,服务端收齐才算报到
        NumenNetwork.sendToServer(new ClientCapsPayload(List.copyOf(ClientMods.provided())));
    }

    static void onHelloAck(HelloAckPayload p) {
        served = true;
        ClientSeats.open(viewer());
        // 召唤卡、设置页要用的这几张表先要来;页面打开时再要一次拿最新的
        for (String kind : List.of(LibraryDesk.PERSONA, LibraryDesk.PROVIDER, LibraryDesk.PREFS, LibraryDesk.SKILL,
                LibraryDesk.NOTES, LibraryDesk.SCHEDULES, LibraryDesk.MCP_CLIENT)) {
            LIBRARY.ask(kind);
        }
    }

    static void onConversations(ConversationsPayload p) {
        saved = List.copyOf(p.conversations());
    }

    /** 断线:这条连接上的东西都不算数了。 */
    public static void disconnected() {
        ClientSeats.close();
        COMPANIONS.clear();
        TRANSCRIPTS.clear();
        saved = List.of();
        LIBRARY.clear();
        McpAuthPrompt.clear();
        served = false;
    }
}
