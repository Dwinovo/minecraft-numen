package com.dwinovo.numen.api.brain;

import com.dwinovo.numen.api.agent.http.CancelToken;
import com.dwinovo.numen.api.agent.llm.ConvoState;
import com.dwinovo.numen.api.agent.llm.LlmEndpoint;
import com.dwinovo.numen.api.agent.loop.Cause;
import com.dwinovo.numen.api.agent.loop.ModelOutcome;
import com.dwinovo.numen.api.agent.loop.ModelPort;
import com.dwinovo.numen.api.agent.loop.ModelRequest;
import com.dwinovo.numen.api.agent.memory.NoteBook;
import com.dwinovo.numen.api.agent.provider.AssistantTurn;
import com.dwinovo.numen.api.agent.provider.Usage;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.agent.loop.Program;
import com.dwinovo.numen.api.program.ProgramHost;
import com.dwinovo.numen.api.store.Io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 无头大脑:产品里的 {@link CompanionLoop},家在临时目录里、写盘在调用线程上直接做;模型是按事先排好的回复答的({@link Scripted}),
 * 主人在不在场、游戏刻由用例拨。没有身体、没有服务器:模型回调与事件都在调用线程上走,{@link #tick} 是服务器的一刻。
 */
final class BrainFixture {

    /** 按事先排好的回复答的"模型";记下每次收到的请求和它是凭哪份档案问的。{@code holding} 时回复先扣着,等 {@link #release}。 */
    static final class Scripted implements Mind {
        final Deque<ModelOutcome> replies = new ArrayDeque<>();
        final List<ModelRequest> asked = new ArrayList<>();
        /** 每次请求带来的档案绑定(谁绑的、哪一条);没绑是 {@code null}。 */
        final List<Bound> askedWith = new ArrayList<>();
        boolean holding;
        private Runnable held;

        Scripted say(String text) {
            replies.add(new ModelOutcome.Answered(new AssistantTurn(text, List.of(), null), Usage.ZERO));
            return this;
        }

        void release() {
            Runnable run = held;
            held = null;
            if (run != null) {
                run.run();
            }
        }

        @Override
        public Cause unavailable(Bound provider) {
            return null;
        }

        @Override
        public LlmEndpoint endpoint(Bound provider) {
            return LlmEndpoint.unresolved("");
        }

        @Override
        public void ask(Bound provider, UUID companion, ModelRequest request, CancelToken cancel,
                        Consumer<ModelPort.Delta> onDelta, Consumer<ModelOutcome> onDone) {
            asked.add(request);
            askedWith.add(provider);
            ModelOutcome reply = replies.poll();
            Runnable answer = () -> onDone.accept(reply);
            if (holding) {
                held = answer;
            } else {
                answer.run();
            }
        }
    }

    /** 这些用例不派程序:有人派就是错。 */
    private static final ProgramHost NO_PROGRAMS = new ProgramHost() {
        @Override
        public void run(UUID companion, String programId, String code, Consumer<Program.Outcome> done) {
            throw new AssertionError("no program was expected: " + code);
        }

        @Override
        public void afterwards(String programId, Consumer<Program.Outcome> late) {
        }

        @Override
        public void forget(String programId) {
        }

        @Override
        public void interrupt(UUID companion, String programId, String why) {
        }

        @Override
        public void cutOff(UUID companion, String programId, boolean stopBody) {
        }

        @Override
        public void stopBody(UUID companion) {
        }
    };

    private static final ToolBook NO_TOOLS = new ToolBook() {
        @Override
        public List<NumenTool> all() {
            return List.of();
        }

        @Override
        public NumenTool resolve(String name) {
            return null;
        }
    };

    /** 札记的落点换成临时目录(全 JVM 一份,换一次)。 */
    private static boolean notes;

    private static synchronized void useTestNotes() throws IOException {
        if (notes) {
            return;
        }
        Path root = Files.createTempDirectory("numen-brain-notes-");
        Io io = Io.direct(e -> { throw new AssertionError(e); });
        NoteBook.init(uuid -> new Home(root.resolve(uuid.toString()), io), () -> 0);
        notes = true;
    }

    final UUID companion;
    /** 她的创建者。 */
    final UUID creator;
    final Path home;
    final Scripted mind;
    /** 她的主人表,创建者在前;用例往里加人就是把她分享出去。 */
    final java.util.List<UUID> keepers = new java.util.ArrayList<>();
    final CompanionLoop loop;
    /** 玩家的名字;不在表里的不知道名字。 */
    final Map<UUID, String> names = new HashMap<>();
    /** 各人的人设库里有的人设正文;不在表里的是一句默认的。 */
    final Map<Bound, String> personas = new HashMap<>();
    boolean ownerOnline = true;
    long gameTime;

    BrainFixture() throws IOException {
        this(UUID.randomUUID(), UUID.randomUUID(), Files.createTempDirectory("numen-brain-"), new Scripted(), 0);
    }

    /** 她的大脑换新(重载存档):同一只、同一个家、同一个"模型",游戏刻接着走。 */
    BrainFixture reloaded() throws IOException {
        return new BrainFixture(companion, creator, home, mind, gameTime);
    }

    private BrainFixture(UUID companion, UUID creator, Path home, Scripted mind, long gameTime) throws IOException {
        this.companion = companion;
        this.creator = creator;
        this.keepers.add(creator);
        this.home = home;
        this.mind = mind;
        this.gameTime = gameTime;
        useTestNotes();
        // 模块库与技能库是全 JVM 一份,别的测试类可能换过:每只无头大脑起来时换成自己的(请求里列她能用的模块与技能)
        var hub = com.dwinovo.numen.api.library.LibraryHub.headless(Files.createTempDirectory("numen-brain-lib-"));
        com.dwinovo.numen.api.script.Modules.init(uuid -> hub.library(com.dwinovo.numen.api.script.Modules.KIND, creator));
        com.dwinovo.numen.api.agent.skill.SkillRegistry.init(
                uuid -> hub.library(com.dwinovo.numen.api.agent.skill.SkillRegistry.KIND, creator));
        Io io = Io.direct(e -> { throw new AssertionError(e); });
        loop = new CompanionLoop(new CompanionLoop.Parts(companion, creator, new Home(home, io), Runnable::run, mind,
                NO_PROGRAMS, NO_TOOLS, () -> null, () -> null, () -> null, () -> ownerOnline, () -> false, () -> 5,
                bound -> personas.getOrDefault(bound, "A companion under test."), player -> names.getOrDefault(player, ""),
                () -> null, () -> 6000L, () -> this.gameTime,
                (speaker, who, conversation) -> "", () -> keepers));
        // 绑一个人设:系统提示的人设层就取它,不去问加载器的配置(无头时没有)
        loop.bind(Slot.PERSONA, new Bound(creator, EntryId.mine("gt_persona")));
    }

    /** 服务器走 {@code n} 刻。 */
    void tick(int n) {
        for (int i = 0; i < n; i++) {
            gameTime++;
            loop.tick();
        }
    }

    /** 她的日志里的每一条。 */
    List<ConvoState.Msg> transcript() {
        return loop.log().loadLines(100).stream().map(com.dwinovo.numen.api.agent.llm.ConvoLog.Line::msg).toList();
    }

    /** 日志里她听到主人定时的次数。 */
    long scheduledHeard() {
        return transcript().stream().filter(m -> m instanceof ConvoState.Msg.User u
                && com.dwinovo.numen.api.agent.llm.ConvoLog.scheduled(u.content())).count();
    }

    /** 收到的请求里主人那一侧说的话,连成一串。 */
    String toldIn(ModelRequest request) {
        return request.messages().stream().filter(m -> m instanceof ConvoState.Msg.User)
                .map(m -> ((ConvoState.Msg.User) m).content()).reduce("", String::concat);
    }
}
