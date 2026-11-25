package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.brain.Bound;
import com.dwinovo.numen.api.brain.Mind;
import com.dwinovo.numen.api.brain.Presence;
import com.dwinovo.numen.api.brain.Slot;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.home.Home;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.library.Switches;
import com.dwinovo.numen.api.mind.ClientBrains;
import com.dwinovo.numen.api.mind.LoopHost;
import com.dwinovo.numen.api.script.HerModules;
import com.dwinovo.numen.api.seat.SeatPipe;
import com.dwinovo.numen.api.seat.ServerSeats;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * GameTest 里一只同伴的大脑,扮的主人客户端:产品里那一份大脑({@link LoopHost})经进程内的管道({@link SeatPipe})坐在这台服务器真实的座位表上,
 * 模型是用例给的({@link Mind}),别的全是产品里的部件——座位、认主人、续传、程序在服务端跑、回执回到模型。大脑的线程是服务端主线程:座位上来的
 * 回调排在这里,每个服务器刻由 {@link #tickAll} 做掉、让大脑往前走一步(用例的步骤每刻都经它)。
 *
 * <p>线程:服务端主线程(用例所在)。
 */
public final class GameBrain {

    private static final java.util.concurrent.Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();
    private static final List<GameBrain> ALL = new CopyOnWriteArrayList<>();

    /** 这一只的模型只会用到跑脚本这一个工具。 */
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

    private final MinecraftServer server;
    private final NumenPlayer her;
    private final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
    private final SeatPipe pipe;
    private final ClientBrains brains;
    private final HerModules modules;
    private final LoopHost host;

    private GameBrain(MinecraftServer server, NumenPlayer her, Mind mind, Path dir) {
        this.server = server;
        this.her = her;
        UUID owner = her.getOwnerUuid();
        Presence.reported(owner, Presence.PROTOCOL);
        Presence.ready(owner, Set.of());
        CompanionRegistry registry = CompanionRegistry.get(server);
        if (registry.find(her.getUUID()) == null) {
            registry.put(her.getUUID(), new CompanionRegistry.Entry(her.getGameProfile().getName(), owner,
                    Level.OVERWORLD, her.blockPosition()));
        }
        this.pipe = ServerSeats.pipe(owner, main::add, THREADS);
        var io = NumenIo.inline("numen-gametest");
        Home library = new Home(dir.resolve("library"), io);
        this.modules = new HerModules(library, new HerModules.Builtins() {
            @Override
            public Set<String> names() {
                return com.dwinovo.numen.api.script.BuiltinModules.all().keySet();
            }

            @Override
            public boolean loaded() {
                return true;
            }

            @Override
            public String code(String name) {
                var builtin = com.dwinovo.numen.api.script.BuiltinModules.get(name);
                return builtin == null ? null : builtin.code();
            }
        });
        LibraryHub hub = LibraryHub.local(library, Switches.inMemory(), List.of(), Set.of());
        this.brains = new ClientBrains(pipe.client(), main::add, companion -> {
            LoopHost made = new LoopHost(new LoopHost.Parts(companion, owner, new Home(dir.resolve("her"), io),
                    main::add, pipe.client(), mind, SCRIPT_ONLY, () -> false, () -> EventQueue.DEFAULT_LEVEL,
                    bound -> "a GameTest persona", builtins -> modules,
                    bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled), id -> "owner"));
            made.bind(Slot.PERSONA, new Bound(owner, new EntryId("my", "gametest")));
            return made;
        });
        brains.sit(Set.of(her.getUUID()));
        this.host = brains.get(her.getUUID());
        ALL.add(this);
    }

    /** 她的大脑坐上座位:家在临时目录里,模型是 {@code mind}。 */
    public static GameBrain sit(MinecraftServer server, NumenPlayer her, Mind mind) {
        try {
            return new GameBrain(server, her, mind, Files.createTempDirectory("numen-gametest-brain-"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 她的大脑。 */
    public LoopHost host() {
        return host;
    }

    /** 她的模块(主人客户端上的)。 */
    public HerModules modules() {
        return modules;
    }

    /** 此刻向座位要她的状态(服务端写的运行期状态)。 */
    public java.util.concurrent.CompletableFuture<com.dwinovo.numen.api.seat.SeatClient.Sitting> state() {
        return pipe.client().state(her.getUUID());
    }

    /** 座位坐上、目录取到了:大脑可以开工。 */
    public boolean ready() {
        return host.catalog() != com.dwinovo.numen.api.seat.Catalog.EMPTY;
    }

    /** 每个服务器刻一次:座位上来的回调做掉,大脑往前走一步。 */
    public static void tickAll() {
        for (GameBrain brain : ALL) {
            brain.tick();
        }
    }

    private void tick() {
        for (Runnable task; (task = main.poll()) != null; ) {
            task.run();
        }
        brains.tick();
    }

    /** 用完:大脑收掉,座位放掉,名册上的她除掉。 */
    public void close() {
        ALL.remove(this);
        brains.leave();
        pipe.disconnect();
        CompanionRegistry.get(server).remove(her.getUUID());
    }
}
