package com.dwinovo.numen.bench;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Hold;
import com.dwinovo.numen.api.agent.loop.LoopEvent;
import com.dwinovo.numen.api.agent.loop.LoopStatus;
import com.dwinovo.numen.api.agent.loop.RunEnd;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.prompt.NumenPrompts;
import com.dwinovo.numen.api.agent.request.SystemPromptComposer;
import com.dwinovo.numen.api.brain.CompanionBrains;
import com.dwinovo.numen.api.brain.CompanionLoop;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.brain.Feed;
import com.dwinovo.numen.api.brain.Venue;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.script.Modules;
import com.dwinovo.numen.api.store.Io;
import com.dwinovo.numen.bench.report.EndReason;
import com.dwinovo.numen.bench.report.FailureTag;
import com.dwinovo.numen.bench.report.Pricing;
import com.dwinovo.numen.bench.report.Run;
import com.dwinovo.numen.bench.report.Variant;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.gametest.OwnerLine;
import com.dwinovo.numen.api.network.Fragments;
import com.dwinovo.numen.api.network.NumenNetwork;
import com.dwinovo.numen.api.network.Wire;
import com.dwinovo.numen.api.network.payload.ClientCallPayload;
import com.dwinovo.numen.api.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.api.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.api.network.payload.FragmentPayload;
import com.dwinovo.numen.api.program.ClientEndpoint;
import com.dwinovo.numen.api.permission.ConsentDesk;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 一次运行:拨回世界、清掉图纸库、搭一块新场地、请主人上线、召出她、接上大脑、搭场景、主人开口;之后每刻取信箱、推循环、看收不收场;收场时
 * 对终态判断言、记一行、把场地收拾干净。
 *
 * <h2>怎么算收场</h2>
 * 按先后看:她死了;调模型的次数超了预算;调模型失败且不再重试(或端点不可用);游戏刻或墙钟超了预算;
 * 她闲下来并保持 {@link #SETTLE_TICKS} 刻——没在跑的对话、身体没有后台活、队里没有会叫醒她的条目、信箱是空的。
 * 最后一种是她自己收工;收工前最后一个失败的结果是主人拒绝或规则不许,记作权限被拒。
 *
 * <h2>她的大脑</h2>
 * 就是产品里服务端的大脑({@link CompanionLoop},经 {@link CompanionBrains#create} 建),只换三样:模型({@link Mind},真实的或基线)、
 * 人设(内置默认人设)、主动性(默认档位)。程序在服务端跑、世界上的事直接进她的收件箱、死亡与复活走产品的路——评测不另接一遍。
 *
 * <h2>主人</h2>
 * 主人是一个在线的模拟玩家,他的连接是 {@link OwnerLine}:服务端发给他的模组载荷由它截下,这里只处理征询卡
 * (分片的消息进主人的收件箱拼回,{@link NumenNetwork#assembled},编解码一遍),由剧本答复。主人没有客户端,所以大脑推给他的界面事件
 * 用不着,只有落进她日志的世界事件({@code <events>})记进这次的记录。
 */
final class Attempt {

    private static final Logger LOG = LoggerFactory.getLogger("numen-bench");

    /** 搭好场景之后等几刻再开口:让搭场景时发生的事先落进她的收件箱,她的那一轮先跑完。 */
    private static final int WARMUP_TICKS = 20;
    /**
     * 服务器目录下的图纸库(设计也存在里面):全服共用、跨次保留,她上一次画的设计会原样出现在下一次里、还占着名字。评测的
     * 游戏目录只给评测用,每次运行开场整个清掉。
     */
    private static final String LIBRARY = "schematics";
    /** 她闲下来保持这么久算收工。 */
    static final int SETTLE_TICKS = 60;
    /** 评测里她绑的人设的 id;正文是内置默认人设,与档案、服主的配置无关。 */
    private static final String BENCH_PERSONA = "bench";
    /** 她叫这个名字,主人叫那个。 */
    private static final String HER_NAME = "Numen";
    private static final String OWNER_NAME = "Owner";
    /** 收工前最后一个失败的结果里有这些字样,是权限层挡下的。 */
    private static final List<String> DENIALS = List.of("refused by the owner", "denied by rule");

    final String suite;
    final Scenario scenario;
    final Variant variant;
    final int number;
    private final Settings settings;
    private final ServerLevel level;
    private final BlockPos origin;
    /** 主线程的信箱:模型的回调、截下的下行包都先进这里,每刻开头取出来执行。 */
    private final Queue<Runnable> mail = new ConcurrentLinkedQueue<>();
    /** 服务端发来的、拼装中的分片消息:主人客户端的收件箱。 */
    private final Fragments.Inbox fromServer = new Fragments.Inbox(Wire.TO_CLIENT);

    private Path home;
    private Transcript transcript;
    private Meter meter;
    private Mind mind;
    private Scene scene;
    private ServerPlayer owner;
    /** 主人的连接:服务端发给主人的模组载荷停在它的队列里,每刻开头取走处理。 */
    private OwnerLine ownerLine;
    private NumenPlayer her;
    private CompanionLoop loop;
    private String promptHash = "";
    private final long startMs = System.currentTimeMillis();
    private long ticks;
    private long spokeAt = -1;
    private int idleTicks;
    private int consents;
    private int ownerReplies;
    private EndReason end;
    private String error;

    Attempt(String suite, Scenario scenario, Variant variant, int number, Settings settings, ServerLevel level,
            BlockPos origin) {
        this.suite = suite;
        this.scenario = scenario;
        this.variant = variant;
        this.number = number;
        this.settings = settings;
        this.level = level;
        this.origin = origin;
    }

    void start() throws IOException {
        LOG.info("[numen-bench] {}/{} {} #{} 开始,场地 {}", suite, scenario.id(), variant.id(), number,
                origin.toShortString());
        MinecraftServer server = level.getServer();
        transcript = new Transcript(Results.get().transcript(suite, scenario.id(), variant.id(), number));
        meter = new Meter(transcript);
        settleWorld(level);
        Path library = server.getServerDirectory().resolve(LIBRARY);
        if (Files.exists(library)) {
            deleteTree(library);
        }
        scenario.arena().build(level, origin);
        GameProfile ownerProfile = new GameProfile(UUID.randomUUID(), OWNER_NAME);
        owner = new ServerPlayer(server, level, ownerProfile, ClientInformation.createDefault());
        ownerLine = new OwnerLine(true);
        server.getPlayerList().placeNewPlayer(ownerLine, owner, CommonListenerCookie.createInitial(ownerProfile, false));
        Vec3 ownerAt = standOn(scenario.ownerAt());
        owner.teleportTo(level, ownerAt.x, ownerAt.y, ownerAt.z, 0, 0);
        // 考场只在平台当场接纳她的环境里跑(NeoForge):回调在调用里就跑完了
        CompanionFactory.spawn(server, UUID.randomUUID(), HER_NAME, owner.getUUID(), level, standOn(scenario.start()),
                () -> true, arrival -> her = arrival.body());
        if (her == null) {
            throw new IllegalStateException("评测的考场里平台没有当场接纳她");
        }
        scene = new Scene(level, origin, her, owner);
        // 札记、会话日志、她的 Lua 模块与技能落在这次自己的临时目录里,收场整个删掉:每次都从白纸开始,模块与技能只用内置原版
        // (jar 同步下来的)、不读主人目录里的,评测也从不读它们
        home = Files.createTempDirectory("numen-bench-");
        Io io = NumenIo.inline("numen-bench");
        NoteBook.init(uuid -> new Home(home.resolve("memory"), io), () -> (int) (level.getDayTime() / 24000L));
        LibraryHub libraries = LibraryHub.headless(home.resolve("library"));
        UUID libraryOwner = owner.getUUID();
        Modules.init(uuid -> libraries.library(Modules.KIND, libraryOwner));
        SkillRegistry.init(uuid -> libraries.library(SkillRegistry.KIND, libraryOwner));
        BooleanSupplier mayCall = () -> meter.turns <= scenario.budget().turns();
        LlmEndpoint endpoint = settings.endpoint();
        mind = switch (variant) {
            case SOLUTION -> new Mind.Scripted(endpoint, mayCall, () -> scenario.solution(scene), "做好了。");
            case NOOP -> new Mind.Scripted(endpoint, mayCall, () -> null, "好的。");
            case LIVE -> new Mind.Live(endpoint, mayCall);
        };
        // 大脑先于场景:搭场景时发生的事(一只女仆死了)直接进她的收件箱,在主人开口前就叫醒她
        CompanionRegistry.get(server).put(her.getUUID(), new CompanionRegistry.Entry(HER_NAME, owner.getUUID(),
                level.dimension(), her.blockPosition()));
        loop = CompanionBrains.create(server, her.getUUID(), owner.getUUID(), new Home(home.resolve("her"), io), mind,
                new OwnerEnd(), () -> true, () -> false, () -> EventQueue.DEFAULT_LEVEL, id -> NumenPrompts.DEFAULT_PERSONA);
        loop.bind(com.dwinovo.numen.api.brain.Slot.PERSONA, BENCH_PERSONA);
        loop.subscribe(meter);
        loop.subscribe(this::onLoopEvent);
        scenario.setup(scene);
        promptHash = sha256(SystemPromptComposer.compose(NumenPrompts.DEFAULT_PERSONA,
                Modules.of(her.getUUID()), SkillRegistry.of(her.getUUID()))).substring(0, 12);
    }

    /** 每个服务端 tick 一次。 */
    void tick() {
        ticks++;
        for (CustomPacketPayload payload; (payload = ownerLine.sent.poll()) != null; ) {
            CustomPacketPayload sent = payload;
            mail.add(() -> downlink(sent));
        }
        for (Runnable job; (job = mail.poll()) != null; ) {
            job.run();
        }
        if (end != null) {
            return;
        }
        if (spokeAt < 0) {
            // 搭场景时发生的事(一只女仆死了)会在主人开口前就叫醒她:她的那一轮先跑完,主人再开口
            if (ticks >= WARMUP_TICKS && idle()) {
                spokeAt = ticks;
                ownerSays(scenario.opening());
            } else if (ticks > scenario.budget().ticks()) {
                end(EndReason.TICK_LIMIT, null);
            }
            return;
        }
        judge();
    }

    /**
     * 她闲下来了:没在跑的对话、没停牌、身体没有后台活、队里没有会叫醒她的条目。
     */
    private boolean idle() {
        LoopStatus status = loop.status();
        return status.phase() == null && status.hold() == null && !status.bodyTaskRunning()
                && !loop.inbox().hasWaking();
    }

    private void judge() {
        if (end != null) {
            return;
        }
        Budget budget = scenario.budget();
        if (scene.died()) {
            end(EndReason.DIED, null);
        } else if (meter.turns > budget.turns()) {
            end(EndReason.TURN_LIMIT, null);
        } else if (meter.apiFailure != null) {
            Mind.Failure failure = mind.lastFailure();
            end(failure != null && failure.contextOverflow() ? EndReason.CONTEXT_OVERFLOW : EndReason.API_ERROR,
                    meter.apiFailure);
        } else if (ticks - spokeAt > budget.ticks()) {
            end(EndReason.TICK_LIMIT, null);
        } else if (System.currentTimeMillis() - startMs > budget.wallMs()) {
            end(EndReason.WALL_LIMIT, null);
        } else if (idle() && mail.isEmpty()) {
            if (++idleTicks >= SETTLE_TICKS) {
                String last = meter.lastFailedResult;
                end(last != null && DENIALS.stream().anyMatch(last::contains) ? EndReason.DENIED : EndReason.DONE,
                        null);
            }
        } else {
            idleTicks = 0;
        }
    }

    /** 评测自己出了错:记下原话,按评测出错收场。 */
    void crash(Throwable t) {
        LOG.error("[numen-bench] {}/{} {} #{} 评测出错", suite, scenario.id(), variant.id(), number, t);
        if (end == null || end != EndReason.HARNESS_ERROR) {
            end = EndReason.HARNESS_ERROR;
            error = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    boolean ended() {
        return end != null;
    }

    /** 这一次停在余额不足上:再调也是白调。 */
    boolean outOfBalance() {
        Mind.Failure failure = mind == null ? null : mind.lastFailure();
        return failure != null && failure.outOfBalance();
    }

    private void end(EndReason reason, String why) {
        end = reason;
        error = why;
    }

    /** 判断言、记一行、收拾场地。 */
    Run finish() {
        if (loop != null) {
            loop.dispose();
        }
        List<Run.Check> checks = new ArrayList<>();
        List<Run.Check> subgoals = new ArrayList<>();
        boolean passed = end != EndReason.HARNESS_ERROR;
        Map<String, Double> metrics = Map.of();
        if (scene != null) {
            List<Check> all = new ArrayList<>(scenario.checks());
            all.add(Check.guard("她没死", s -> s.assertTrue(!s.died(), "她死了")));
            for (Check check : all) {
                Run.Check result = judge(check);
                (check.kind() == Check.Kind.SUBGOAL ? subgoals : checks).add(result);
                passed &= check.kind() == Check.Kind.SUBGOAL || result.passed();
            }
            metrics = scenario.metrics(scene);
        }
        boolean live = variant == Variant.LIVE;
        Pricing.Price price = live ? settings.pricing().of(settings.modelLabel()) : null;
        FailureTag tag = FailureTag.auto(passed, end, meter != null && meter.commandError);
        Run run = new Run(suite, scenario.id(), variant.id(), number, settings.commit(), promptHash,
                live ? settings.modelLabel() : variant.id(), passed, checks, subgoals, end.name(),
                meter == null ? 0 : meter.turns, meter == null ? 0 : meter.toolCalls,
                meter == null ? 0 : meter.toolErrors, meter == null ? 0 : meter.repeatedFailures, consents,
                meter == null ? 0 : meter.tokensMiss, meter == null ? 0 : meter.tokensHit,
                meter == null ? 0 : meter.tokensOut,
                price == null ? null : price.cost(meter.tokensMiss, meter.tokensHit, meter.tokensOut),
                price == null ? null : price.currency(), System.currentTimeMillis() - startMs, ticks,
                !passed && end == EndReason.DONE, tag == null ? null : tag.name(),
                meter == null ? "" : meter.finalWords,
                transcript == null ? "" : Results.get().dir().relativize(transcript.file()).toString()
                        .replace('\\', '/'),
                error, metrics, meter == null ? List.of() : meter.functions());
        if (transcript != null) {
            transcript.write("end", "end", end.name(), "passed", String.valueOf(passed));
        }
        LOG.info("[numen-bench] {}/{} {} #{} 收场:{}{},{} 轮,{} 个调用", suite, scenario.id(), variant.id(),
                number, end.words(), passed ? ",通过" : ",没过", run.turns(), run.toolCalls());
        cleanUp();
        return run;
    }

    private Run.Check judge(Check check) {
        try {
            check.assertion().accept(scene);
            return new Run.Check(check.name(), check.kind().id(), true, "");
        } catch (GameTestAssertException failed) {
            return new Run.Check(check.name(), check.kind().id(), false, String.valueOf(failed.getMessage()));
        }
    }

    /** 她、主人、掉在地上的东西、场地本身,一样不留;临时目录删掉。 */
    private void cleanUp() {
        MinecraftServer server = level.getServer();
        if (her != null) {
            CompanionLoops.dismissed(server, her.getUUID());
            CompanionRegistry.get(server).remove(her.getUUID());
            CompanionFactory.despawn(server, her);
        }
        if (owner != null) {
            server.getPlayerList().remove(owner);
        }
        for (Entity e : level.getEntities((Entity) null, scenario.arena().bounds(origin), e -> !(e instanceof Player))) {
            e.discard();
        }
        scenario.arena().clear(level, origin);
        if (home != null) {
            deleteTree(home);
        }
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- 主人 ----

    private void ownerSays(String words) {
        transcript.write("owner_says", "text", words);
        loop.submitPrompt(words);
    }

    /**
     * 她一轮说完停下来:剧本要回一句就回(经信箱,不在内核发事件的当口推进它);她死了:记下、收场的判断认。
     */
    private void onLoopEvent(LoopEvent event) {
        if (event instanceof LoopEvent.RunEnded ended && ended.end() instanceof RunEnd.Done
                && !meter.finalWords.isEmpty()) {
            String reply = scenario.owner().reply(meter.finalWords, ownerReplies);
            if (reply != null) {
                ownerReplies++;
                mail.add(() -> ownerSays(reply));
            }
        }
        if (event instanceof LoopEvent.HoldChanged held && held.hold() == Hold.DEAD) {
            transcript.write("died", "cause", java.util.Objects.toString(her.deathMessage(), ""));
            scene.markDied();
        }
    }

    /**
     * 她的大脑往主人那一头推的东西。评测的主人没有客户端,界面要的那些用不着;只把落进她日志的世界事件记进这次的记录。
     */
    private final class OwnerEnd implements CompanionLoops.Link {

        @Override
        public boolean hasClient() {
            return true;
        }

        @Override
        public void send(UUID companion, List<Feed> events) {
            for (Feed feed : events) {
                if (feed instanceof Feed.Line line && line.line().msg() instanceof ConvoState.Msg.User user
                        && user.content().startsWith("<events>")) {
                    transcript.write("event", "text", Transcript.clip(user.content()));
                }
            }
        }

        @Override
        public void said(UUID companion, String said, Venue venue, String conversation) {
        }

        @Override
        public void activities(Map<UUID, LoopStatus.Doing> doing) {
        }
    }

    private void consent(ConsentRequestPayload request) {
        if (request.withdrawn()) {
            transcript.write("consent_withdrawn", "why", String.valueOf(request.withdrawnBecause()));
            return;
        }
        consents++;
        OwnerScript.Answer answer = scenario.owner().consent(request);
        String asked = request.lines().stream()
                .map(l -> l.kind() + " " + l.count() + " " + l.name().getString())
                .collect(Collectors.joining(", "));
        transcript.write("consent", "asked", asked, "answer", answer.decision().name());
        ConsentDesk.reply(owner, her, request.id(), answer.decision(), answer.note());
    }

    // ---- 主人的连接 ----

    /**
     * 主人客户端收到这个包会做什么,这里照做;别的包(名册、定位、状态、调试画线、界面动作)这里用不着。分片的消息先拼回;
     * 服务端要主人客户端执行的函数({@code ClientCall})由客户端执行体答,答复照网络上的样子上行,交给服务端真实的入口。
     */
    private void downlink(CustomPacketPayload payload) {
        UUID uuid = her.getUUID();
        switch (payload) {
            case FragmentPayload p -> {
                CustomPacketPayload whole = NumenNetwork.assembled(fromServer, wire(FragmentPayload.TO_CLIENT_CODEC, p));
                if (whole != null) {
                    downlink(whole);
                }
            }
            case ClientCallPayload p when p.entityUuid().equals(uuid) -> ClientEndpoint.handle(
                    wire(ClientCallPayload.STREAM_CODEC, p), result -> ClientCallResultPayload.handle(
                            Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, result), owner));
            case ConsentRequestPayload p when p.companion().equals(uuid) ->
                    consent(wire(ConsentRequestPayload.STREAM_CODEC, p));
            default -> { }
        }
    }

    /** 按网络上的样子编一遍再解一遍:她拿到的和主人客户端从线上收到的是同一份。 */
    private <T> T wire(StreamCodec<? super RegistryFriendlyByteBuf, T> codec, T payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
        try {
            codec.encode(buf, payload);
            return codec.decode(buf);
        } finally {
            buf.release();
        }
    }

    // ---- 小工具 ----

    /**
     * 评测的世界:和平、正午且不走时间、晴天、不刷怪。每次运行开场都拨回这个样子,不受上一次跑了多久影响;场景要别的
     * (夜里、有难度)就在搭场景时改,只管这一次。
     */
    private static void settleWorld(ServerLevel level) {
        level.getServer().setDifficulty(Difficulty.PEACEFUL, true);
        level.setDayTime(6000);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setWeatherParameters(24000, 0, false, false);
    }

    /** 场地里一格的正中,站在它上面。 */
    private Vec3 standOn(BlockPos rel) {
        BlockPos at = origin.offset(rel);
        return new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
