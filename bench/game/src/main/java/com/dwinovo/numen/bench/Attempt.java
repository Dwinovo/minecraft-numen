package com.dwinovo.numen.bench;

import com.dwinovo.numen.agent.loop.HaltReason;
import com.dwinovo.numen.agent.loop.LoopEvent;
import com.dwinovo.numen.agent.loop.RunEnd;
import com.dwinovo.numen.agent.memory.NoteBook;
import com.dwinovo.numen.agent.prompt.NumenPrompts;
import com.dwinovo.numen.agent.provider.ProviderRegistry;
import com.dwinovo.numen.agent.request.BodySnapshot;
import com.dwinovo.numen.agent.request.SystemPromptComposer;
import com.dwinovo.numen.bench.report.EndReason;
import com.dwinovo.numen.bench.report.FailureTag;
import com.dwinovo.numen.bench.report.Pricing;
import com.dwinovo.numen.bench.report.Run;
import com.dwinovo.numen.bench.report.Variant;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.network.Fragments;
import com.dwinovo.numen.network.NumenNetwork;
import com.dwinovo.numen.network.Wire;
import com.dwinovo.numen.network.payload.CancelTasksPayload;
import com.dwinovo.numen.network.payload.ConsentRequestPayload;
import com.dwinovo.numen.network.payload.CurrentTaskPayload;
import com.dwinovo.numen.network.payload.FragmentPayload;
import com.dwinovo.numen.network.payload.ClientCallPayload;
import com.dwinovo.numen.network.payload.ClientCallResultPayload;
import com.dwinovo.numen.network.payload.NumenDeathPayload;
import com.dwinovo.numen.network.payload.NumenEventPayload;
import com.dwinovo.numen.network.payload.NumenStatePayload;
import com.dwinovo.numen.network.payload.ProgramResultPayload;
import com.dwinovo.numen.network.payload.RunProgramPayload;
import com.dwinovo.numen.network.payload.StopProgramPayload;
import com.dwinovo.numen.program.ProgramUplink;
import com.dwinovo.numen.permission.ConsentDesk;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * <h2>上行与下行</h2>
 * 整段程序经 {@link ProgramUplink#wire} 直接交给服务端真实入口 {@link RunProgramPayload#handle}(停止、客户端函数的答复同样),发送者是
 * 模拟主人,上行的包按网络上的样子过一遍({@link Fragments#crossed}:超过单包上限的切成片再拼回、编解码);发给主人的模组载荷由
 * {@link OwnerConnection} 截下,分片的消息进主人的收件箱拼回({@link NumenNetwork#assembled}),也编解码一遍,再照主人客户端的
 * 处理方式交给大脑:程序的回执与每次调用的结局给上行部件、服务端要客户端执行的函数交给客户端执行体、当前任务与身体状态给运行期状态、
 * 世界事件进收件箱、征询由剧本答复、死亡切断循环。
 */
final class Attempt {

    private static final Logger LOG = LoggerFactory.getLogger("numen-bench");

    /** 搭好场景之后等几刻再开口:让服务端先把她的身体状态推过来,第一次请求里就有背包。 */
    private static final int WARMUP_TICKS = 20;
    /** 等身体状态最多等这么久,还没来就是评测自己出了问题。 */
    private static final int WARMUP_LIMIT = 200;
    /**
     * 服务器目录下的图纸库(设计也存在里面):全服共用、跨次保留,她上一次画的设计会原样出现在下一次里、还占着名字。评测的
     * 游戏目录只给评测用,每次运行开场整个清掉。
     */
    private static final String LIBRARY = "schematics";
    /** 她闲下来保持这么久算收工。 */
    static final int SETTLE_TICKS = 60;
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
    private NumenPlayer her;
    private Brain brain;
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
        owner = OwnerConnection.join(server, level, OWNER_NAME, standOn(scenario.ownerAt()),
                payload -> mail.add(() -> downlink(payload)));
        her = CompanionFactory.spawn(server, UUID.randomUUID(), HER_NAME, owner.getUUID(), level,
                standOn(scenario.start()));
        scene = new Scene(level, origin, her, owner);
        // 札记、会话日志与她的 Lua 模块落在这次自己的临时目录里,收场整个删掉:每次都从白纸开始,模块只用内置原版、不读主人目录
        // 里的,评测也从不读它们
        home = Files.createTempDirectory("numen-bench-");
        NoteBook.init(uuid -> home.resolve("memory"), () -> (int) (level.getDayTime() / 24000L));
        com.dwinovo.numen.script.Modules.init(uuid -> home.resolve("lua"));
        scenario.setup(scene);
        mind = switch (variant) {
            case SOLUTION -> new Mind.Scripted(scenario.solution(scene), "做好了。");
            case NOOP -> new Mind.Scripted(null, "好的。");
            case LIVE -> new Mind.Live(settings.endpoint());
        };
        int window = ProviderRegistry.contextWindow(ProviderRegistry.canonicalId(settings.provider()),
                settings.model());
        brain = new Brain(her.getUUID(), mind, home.resolve("chat.jsonl"), window, mail,
                () -> meter.turns <= scenario.budget().turns(), level::getDayTime);
        brain.subscribe(meter);
        brain.subscribe(this::onLoopEvent);
        ProgramUplink.wire = this::uplink;
        promptHash = sha256(SystemPromptComposer.compose(NumenPrompts.DEFAULT_PERSONA,
                com.dwinovo.numen.script.Modules.of(her.getUUID()))).substring(0, 12);
    }

    /** 每个服务端 tick 一次。 */
    void tick() {
        ticks++;
        for (Runnable job; (job = mail.poll()) != null; ) {
            job.run();
        }
        if (end != null) {
            return;
        }
        if (spokeAt < 0) {
            if (!brain.hasBody()) {
                if (ticks > WARMUP_LIMIT) {
                    end(EndReason.HARNESS_ERROR, "她的身体状态 " + WARMUP_LIMIT + " 刻里一直没推给主人");
                }
                return;
            }
            // 搭场景时发生的事(一只女仆死了)会在主人开口前就叫醒她:她的那一轮先跑完,主人再开口
            brain.loop.tick();
            if (ticks >= WARMUP_TICKS && brain.idle()) {
                spokeAt = ticks;
                ownerSays(scenario.opening());
            } else if (ticks > scenario.budget().ticks()) {
                end(EndReason.TICK_LIMIT, null);
            }
            return;
        }
        brain.loop.tick();
        judge();
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
        } else if (brain.idle() && mail.isEmpty()) {
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
        if (brain != null) {
            brain.dispose();
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
        ProgramUplink.wire = payload -> {
            throw new IllegalStateException("没有在跑的评测,不该有上行:" + payload.type().id());
        };
        ProgramUplink.CONNECTION.disconnected();
        if (her != null) {
            CompanionFactory.despawn(server, her);
            EventOutbox.get(server).forget(her.getUUID());
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
        brain.ownerSays(words);
    }

    /** 她一轮说完停下来:剧本要回一句就回(经信箱,不在内核发事件的当口推进它)。 */
    private void onLoopEvent(LoopEvent event) {
        if (event instanceof LoopEvent.RunEnded ended && ended.end() instanceof RunEnd.Done
                && !meter.finalWords.isEmpty()) {
            String reply = scenario.owner().reply(meter.finalWords, ownerReplies);
            if (reply != null) {
                ownerReplies++;
                mail.add(() -> ownerSays(reply));
            }
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

    // ---- 上行与下行 ----

    private void uplink(CustomPacketPayload payload) {
        switch (payload) {
            case RunProgramPayload p ->
                    RunProgramPayload.handle(Fragments.crossed(Wire.TO_SERVER, RunProgramPayload.STREAM_CODEC, p), owner);
            case StopProgramPayload p ->
                    StopProgramPayload.handle(Fragments.crossed(Wire.TO_SERVER, StopProgramPayload.STREAM_CODEC, p), owner);
            case ClientCallResultPayload p -> ClientCallResultPayload.handle(
                    Fragments.crossed(Wire.TO_SERVER, ClientCallResultPayload.STREAM_CODEC, p), owner);
            case CancelTasksPayload p ->
                    CancelTasksPayload.handle(Fragments.crossed(Wire.TO_SERVER, CancelTasksPayload.STREAM_CODEC, p), owner);
            default -> throw new IllegalStateException("评测的上行只有程序、叫停、客户端函数的答复,来了 "
                    + payload.type().id());
        }
    }

    /** 主人客户端收到这个包会做什么,这里照做;别的包(名册、定位、调试画线、界面动作)这里用不着。 */
    private void downlink(CustomPacketPayload payload) {
        UUID uuid = her.getUUID();
        switch (payload) {
            case FragmentPayload p -> {
                CustomPacketPayload whole = NumenNetwork.assembled(fromServer, wire(FragmentPayload.TO_CLIENT_CODEC, p));
                if (whole != null) {
                    downlink(whole);
                }
            }
            case ProgramResultPayload p when p.entityUuid().equals(uuid) ->
                    ProgramResultPayload.handle(wire(ProgramResultPayload.STREAM_CODEC, p));
            case ClientCallPayload p when p.entityUuid().equals(uuid) ->
                    ClientCallPayload.handle(wire(ClientCallPayload.STREAM_CODEC, p));
            case CurrentTaskPayload p when p.entityUuid().equals(uuid) ->
                    brain.runtime.onCurrentTask(wire(CurrentTaskPayload.STREAM_CODEC, p));
            case NumenStatePayload p when p.uuid().equals(uuid) ->
                    brain.body(BodySnapshot.of(wire(NumenStatePayload.STREAM_CODEC, p), System.currentTimeMillis()));
            case NumenEventPayload p when p.entityUuid().equals(uuid) -> {
                NumenEventPayload got = wire(NumenEventPayload.STREAM_CODEC, p);
                got.entries().forEach(e -> transcript.write("event", "type", e.type(), "text",
                        Transcript.clip(e.text())));
                brain.loop.push(got.entries());
            }
            case ConsentRequestPayload p when p.companion().equals(uuid) ->
                    consent(wire(ConsentRequestPayload.STREAM_CODEC, p));
            case NumenDeathPayload p when p.entityUuid().equals(uuid) -> {
                transcript.write("died", "cause", p.cause());
                scene.markDied();
                brain.loop.halt(HaltReason.DEATH, p.cause());
            }
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
