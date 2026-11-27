package com.dwinovo.numen.plugins.minecraft.e2e;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConnectionProfile;
import com.dwinovo.numen.api.agent.llm.ModelCatalog;
import com.dwinovo.numen.api.agent.llm.ProviderLibrary;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.LlmMind;
import com.dwinovo.numen.api.owner.OwnerInput;
import com.dwinovo.numen.api.owner.Presence;
import com.dwinovo.numen.api.mind.Slot;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.keys.Keychain;
import com.dwinovo.numen.api.keys.Keys;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mind.ClientBrains;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCapsPayload;
import com.dwinovo.numen.api.network.payload.CompanionListPayload;
import com.dwinovo.numen.api.network.payload.ConsentReplyPayload;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.api.network.payload.ConversationsPayload;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import com.dwinovo.numen.api.network.payload.HelloAckPayload;
import com.dwinovo.numen.api.network.payload.HelloPayload;
import com.dwinovo.numen.api.network.payload.OwnerInputPayload;
import com.dwinovo.numen.api.network.payload.SeatPayload;
import com.dwinovo.numen.api.network.payload.ServerHelloPayload;
import com.dwinovo.numen.api.permission.ConsentAnswer;
import com.dwinovo.numen.api.seat.Catalog;
import com.dwinovo.numen.api.seat.SeatClient;
import com.dwinovo.numen.mcp.rpc.RpcException;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * 无头的主人客户端:一条按原版协议连进服务端的连接({@link Bot}),上面跑 Numen API 里真的那一套——包的编解码与分片、座位({@link SeatClient})、
 * 大脑({@link ClientBrains}、{@link LoopHost}),模型走真的传输({@link LlmMind})、真的模型档案与钥匙串,只是对面是场景里的假端点
 * ({@link FakeModel})。收到的征询记在 {@link #consents()}。大脑的线程是自己的一条单线程,每 50 毫秒让大脑走一步。
 *
 * <p>线程:网络线程收包,大脑的线程处理;场景线程用的方法都自己切过去或只读。
 */
final class HeadlessOwner implements AutoCloseable {

    private static final Duration SEAT = Duration.ofSeconds(60);
    /** 她看得到的工具只有跑脚本这一个:假模型回的只有话和 Lua。 */
    private static final ToolBook SCRIPT_ONLY = new ToolBook() {
        private final NumenTool script = new ScriptTool();

        @Override
        public List<NumenTool> all() {
            return List.of(script);
        }

        @Override
        public NumenTool resolve(String name) {
            return script.name().equals(name) ? script : null;
        }
    };

    private static boolean bootstrapped;

    private final Bot bot;
    private final UUID me;
    private final ScheduledExecutorService main = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "e2e-owner-brain");
        t.setDaemon(true);
        return t;
    });
    private final Fragments.Inbox inbox = new Fragments.Inbox(Wire.TO_CLIENT);
    private final SeatClient seat;
    private final ClientBrains brains;
    private final List<ConsentRequestPayload> consents = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Void> acknowledged = new CompletableFuture<>();
    private final Path home;
    /** 服务端推来会话表的次数:改了会话就推一次。 */
    private final java.util.concurrent.atomic.AtomicInteger conversationPushes = new java.util.concurrent.atomic.AtomicInteger();
    /** 名册上我的同伴,按名字。 */
    private final java.util.Map<String, UUID> named = new java.util.concurrent.ConcurrentHashMap<>();

    private HeadlessOwner(Bot bot, FakeModel model, Spec spec, Path home) throws IOException {
        this.bot = bot;
        this.me = bot.uuid();
        this.home = home;
        var io = NumenIo.inline("e2e-owner");
        Home library = new Home(home.resolve("library"), io);
        LibraryHub hub = LibraryHub.local(library, Switches.inMemory(), List.of(), Set.of());
        Keychain keychain = new Keychain(new Home(home.resolve("keys"), io));
        keychain.put("custom", spec.modelKey());
        ModelCatalog models = new ModelCatalog(home.resolve("models.json"), () -> "");
        ProviderLibrary providers = new ProviderLibrary(hub.library(ProviderLibrary.KIND), new Keys(keychain), models);
        ConnectionProfile profile = ProviderLibrary.read("{\"provider\":\"custom\",\"protocol\":\"chat\","
                + "\"model\":\"e2e-fake\",\"base_url\":\"http://" + spec.host() + ":" + spec.modelPort() + "/v1\"}");
        EntryId profileId = EntryId.parse(providers.create("e2e", profile).id());
        this.seat = new SeatClient(main, Executors.newVirtualThreadPerTaskExecutor(), this::send);
        this.brains = new ClientBrains(seat, main, companion -> {
            LoopHost made = new LoopHost(new LoopHost.Parts(companion, me, new Home(home.resolve(companion.toString()), io),
                    main, seat, new LlmMind(() -> providers, models), SCRIPT_ONLY, () -> false,
                    () -> EventQueue.DEFAULT_LEVEL, bound -> "You are a test companion.",
                    bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), who -> "", "e2e"));
            made.bind(Slot.PROVIDER, new Bound(me, profileId));
            made.bind(Slot.PERSONA, new Bound(me, new EntryId("my", "e2e")));
            return made;
        });
        main.scheduleWithFixedDelay(() -> {
            try {
                brains.tick();
            } catch (RuntimeException broke) {
                System.out.println("[e2e-owner] the brain broke: " + broke);
                broke.printStackTrace(System.out);
            }
        }, 50, 50, TimeUnit.MILLISECONDS);
        bot.onPayload(this::received);
    }

    /** 以这个名字进服,等到服务端收下了这位主人的客户端(它答了报到)。 */
    static HeadlessOwner join(Spec spec, FakeModel model, String name, String locale) throws Exception {
        synchronized (HeadlessOwner.class) {
            if (!bootstrapped) {
                net.minecraft.SharedConstants.tryDetectVersion();
                net.minecraft.server.Bootstrap.bootStrap();
                bootstrapped = true;
            }
        }
        // 客户端能收的 Numen 频道在配置阶段就登记:服务端在登入那一刻据此当它是装了 Numen 的客户端,开场的那一包才发得出来
        Bot bot = Bot.join(spec, name, locale, channels());
        HeadlessOwner owner = new HeadlessOwner(bot, model, spec, Files.createTempDirectory("e2e-owner-"));
        owner.acknowledged.get(SEAT.toSeconds(), TimeUnit.SECONDS);
        return owner;
    }

    Bot bot() {
        return bot;
    }

    UUID uuid() {
        return me;
    }

    /** 收到的征询,按先后。 */
    List<ConsentRequestPayload> consents() {
        return consents;
    }

    // ---- 线上 ----

    private static List<String> channels() {
        return List.of(FragmentPayload.TO_CLIENT.id().toString(), ServerHelloPayload.TYPE.id().toString(),
                HelloAckPayload.TYPE.id().toString(), CompanionListPayload.TYPE.id().toString(),
                SeatPayload.TO_CLIENT.id().toString(), ConsentRequestPayload.TYPE.id().toString(),
                ConversationsPayload.TYPE.id().toString(),
                com.dwinovo.numen.api.network.payload.LibraryPayload.TYPE.id().toString(),
                com.dwinovo.numen.api.network.payload.ClientUiActionPayload.TYPE.id().toString(),
                com.dwinovo.numen.api.network.payload.NumenLocationsPayload.TYPE.id().toString(),
                com.dwinovo.numen.api.network.payload.NumenStatePayload.TYPE.id().toString());
    }

    private void received(String channel, byte[] data) {
        try {
            ByteBuf buf = Unpooled.wrappedBuffer(data);
            if (channel.equals(ServerHelloPayload.TYPE.id().toString())) {
                ServerHelloPayload hello = ServerHelloPayload.STREAM_CODEC.decode(buf);
                if (hello.protocol() == Presence.PROTOCOL) {
                    sendPayload(new HelloPayload(Presence.PROTOCOL));
                    sendPayload(new ClientCapsPayload(List.of()));
                }
            } else if (channel.equals(HelloAckPayload.TYPE.id().toString())) {
                acknowledged.complete(null);
            } else if (channel.equals(CompanionListPayload.TYPE.id().toString())) {
                roster(CompanionListPayload.STREAM_CODEC.decode(registryBuf(buf)));
            } else if (channel.equals(ConversationsPayload.TYPE.id().toString())) {
                conversationPushes.incrementAndGet();
            } else if (channel.equals(ConsentRequestPayload.TYPE.id().toString())) {
                ConsentRequestPayload request = ConsentRequestPayload.STREAM_CODEC.decode(registryBuf(buf));
                if (!request.withdrawn()) {
                    consents.add(request);
                }
            } else if (channel.equals(FragmentPayload.TO_CLIENT.id().toString())) {
                FragmentPayload piece = FragmentPayload.TO_CLIENT_CODEC.decode(buf);
                CustomPacketPayload whole = NumenNetwork.assembled(inbox, piece);
                if (whole instanceof CompanionListPayload list) {
                    roster(list);
                } else if (whole instanceof ConsentRequestPayload request) {
                    if (!request.withdrawn()) {
                        consents.add(request);
                    }
                } else if (whole != null) {
                    seatMessage(whole);
                }
            } else if (channel.equals(SeatPayload.TO_CLIENT.id().toString())) {
                seatMessage(SeatPayload.TO_CLIENT_CODEC.decode(buf));
            }
        } catch (RuntimeException unreadable) {
            System.out.println("[e2e-owner] could not read a payload on " + channel + ": " + unreadable);
        }
    }

    private static RegistryFriendlyByteBuf registryBuf(ByteBuf buf) {
        return new RegistryFriendlyByteBuf(buf, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
    }

    private void seatMessage(CustomPacketPayload payload) {
        if (payload instanceof SeatPayload seatPayload) {
            try {
                seat.receive(seatPayload.message());
            } catch (RpcException unreadable) {
                System.out.println("[e2e-owner] the server sent a seat message the client cannot read: " + unreadable);
            }
        }
    }

    /** 名册变了:创建者是我的同伴的大脑坐上座位。 */
    private void roster(CompanionListPayload list) {
        Set<UUID> mine = new java.util.HashSet<>();
        for (CompanionListPayload.Entry entry : list.companions()) {
            if (!entry.keepers().isEmpty() && entry.keepers().get(0).uuid().equals(me)) {
                mine.add(entry.uuid());
                named.put(entry.name(), entry.uuid());
            }
        }
        main.execute(() -> brains.sit(mine));
    }

    /** 座位上的一条消息送出去:包装、分片、逐片发。 */
    private void send(com.dwinovo.numen.mcp.rpc.RpcMessage message) throws IOException {
        SeatPayload framed = SeatPayload.frame(Wire.TO_SERVER, message);
        for (CustomPacketPayload packet : Fragments.packets(Wire.TO_SERVER, SeatPayload.TO_SERVER_CODEC, framed,
                Unpooled::buffer)) {
            sendPayload(packet);
        }
    }

    private void sendPayload(CustomPacketPayload payload) {
        ByteBuf buf = Unpooled.buffer();
        encode(payload, buf);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        bot.payload(payload.type().id().toString(), bytes);
    }

    @SuppressWarnings("unchecked")
    private static void encode(CustomPacketPayload payload, ByteBuf buf) {
        switch (payload) {
            case HelloPayload p -> HelloPayload.STREAM_CODEC.encode(buf, p);
            case ClientCapsPayload p -> ClientCapsPayload.STREAM_CODEC.encode(buf, p);
            case SeatPayload p -> SeatPayload.TO_SERVER_CODEC.encode(buf, p);
            case FragmentPayload p -> FragmentPayload.TO_SERVER_CODEC.encode(buf, p);
            case ConsentReplyPayload p -> ConsentReplyPayload.STREAM_CODEC.encode(buf, p);
            case OwnerInputPayload p -> OwnerInputPayload.STREAM_CODEC.encode(buf, p);
            default -> throw new IllegalArgumentException("the headless owner does not send " + payload.type().id());
        }
    }

    // ---- 场景做的事 ----

    /** 等名册上有这只同伴。 */
    UUID awaitCompanion(String name) {
        until("her to be on the roster", () -> named.containsKey(name));
        return named.get(name);
    }

    /** 等她的大脑坐上座位:主人表上有她、座位开了、目录取到了。 */
    void awaitSeated(UUID companion) {
        until("her brain to sit at the seat", () -> {
            LoopHost host = brains.get(companion);
            return host != null && host.catalog() != Catalog.EMPTY;
        });
    }

    /** 主人在面板里对她说一句话:交给她的大脑,和面板输入框同一个入口。 */
    void says(UUID companion, String words) {
        main.execute(() -> brains.get(companion).submitPrompt(words));
    }

    /** 她的对话记录里此刻有这句话吗(任何一方说的):向服务端翻一页,和面板读的同一份。 */
    boolean loggedWords(UUID companion, String words) {
        try {
            return seat.page(companion, null, "", com.dwinovo.numen.api.seat.History.PAGE).get(5, TimeUnit.SECONDS).entries().stream()
                    .anyMatch(entry -> entry.msg().toString().contains(words));
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    /** 她的收件箱里此刻有一条带这段话的事件吗(旁听到的话躺在那里,没叫醒她就不进日志)。 */
    boolean inboxHas(UUID companion, String words) {
        try {
            return CompletableFuture.supplyAsync(() -> brains.get(companion).inbox().entries().stream()
                    .anyMatch(e -> e.text().contains(words)), main).get(5, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | java.util.concurrent.TimeoutException e) {
            throw new AssertionError(e);
        }
    }

    /** 对服务端说一件会话的事(拉人、改名……),等服务端把改后的会话表推回来。 */
    void ask(OwnerInput input) {
        int before = conversationPushes.get();
        sendPayload(OwnerInputPayload.of(input));
        until("the conversations after " + input.getClass().getSimpleName(), () -> conversationPushes.get() > before);
    }

    /** 答一条征询,和答复框同一个包。 */
    void answer(ConsentRequestPayload request, ConsentAnswer.Decision decision) {
        sendPayload(new ConsentReplyPayload(request.companion(), request.id(), decision, ""));
    }

    /** 等一个条件成立(别的线程在推进它);到点没有就失败。 */
    void until(String what, BooleanSupplier done) {
        long deadline = System.nanoTime() + SEAT.toNanos();
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    /** 等到收到一条满足条件的征询。 */
    ConsentRequestPayload awaitConsent() {
        until("a consent request", () -> !consents.isEmpty());
        return consents.get(consents.size() - 1);
    }

    @Override
    public void close() {
        main.shutdownNow();
        bot.close();
    }
}
