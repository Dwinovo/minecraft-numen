package com.dwinovo.numen.api.gametest;

import com.dwinovo.numen.api.NumenIo;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.agent.tool.NumenTool;
import com.dwinovo.numen.api.agent.tool.ScriptTool;
import com.dwinovo.numen.api.agent.tool.ToolBook;
import com.dwinovo.numen.api.mind.Bound;
import com.dwinovo.numen.api.mind.Mind;
import com.dwinovo.numen.api.owner.Presence;
import com.dwinovo.numen.api.mind.Slot;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;

/**
 * GameTest 里一只同伴的大脑,扮的主人客户端:产品里那一份大脑({@link LoopHost})经进程内的管道({@link SeatPipe})坐在这台服务器真实的座位表上,
 * 模型是用例给的({@link Mind}),别的全是产品里的部件——座位、认主人、续传、程序在服务端跑、回执回到模型。一位主人的几只同伴共用一条连接
 * (和产品里一台客户端一样)。大脑的线程是服务端主线程:座位上来的回调排在这里,每个服务器刻由 {@link #tickAll} 做掉、让大脑往前走一步
 * (用例的步骤每刻都经它)。
 *
 * <p>线程:服务端主线程(用例所在)。
 */
public final class GameBrain {

    private static final java.util.concurrent.Executor THREADS = Executors.newVirtualThreadPerTaskExecutor();
    /** 每位主人一条连接。 */
    private static final Map<UUID, Link> LINKS = new ConcurrentHashMap<>();

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

    /** 一位主人的客户端:一条座位连接,几只同伴的大脑。 */
    private static final class Link {
        final Queue<Runnable> main = new ConcurrentLinkedQueue<>();
        final SeatPipe pipe;
        final ClientBrains brains;
        final Map<UUID, Mind> minds = new HashMap<>();
        final Map<UUID, Path> dirs = new HashMap<>();
        final Set<UUID> sitting = ConcurrentHashMap.newKeySet();
        final HerModules modules;

        Link(UUID owner, Path dir) {
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
                LoopHost made = new LoopHost(new LoopHost.Parts(companion, owner,
                        new Home(dirs.get(companion).resolve("her"), io), main::add, pipe.client(), minds.get(companion),
                        SCRIPT_ONLY, () -> false, () -> EventQueue.DEFAULT_LEVEL, bound -> "a GameTest persona",
                        builtins -> modules, bundled -> SkillRegistry.of(hub.library(SkillRegistry.KIND), bundled),
                        id -> "owner"));
                made.bind(Slot.PERSONA, new Bound(owner, new EntryId("my", "gametest")));
                return made;
            });
        }
    }

    private final MinecraftServer server;
    private final NumenPlayer her;
    private final Link link;
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
        this.link = LINKS.computeIfAbsent(owner, id -> new Link(id, dir));
        link.minds.put(her.getUUID(), mind);
        link.dirs.put(her.getUUID(), dir);
        link.sitting.add(her.getUUID());
        link.brains.sit(Set.copyOf(link.sitting));
        this.host = link.brains.get(her.getUUID());
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
        return link.modules;
    }

    /** 此刻向座位要她的状态(服务端写的运行期状态)。 */
    public java.util.concurrent.CompletableFuture<com.dwinovo.numen.api.seat.SeatClient.Sitting> state() {
        return link.pipe.client().state(her.getUUID());
    }

    /** 座位坐上、目录取到了:大脑可以开工。 */
    public boolean ready() {
        return host.catalog() != com.dwinovo.numen.api.seat.Catalog.EMPTY;
    }

    /** 座位上还有往返没落定(大脑发出的请求没有答复):有就是一句说明,没有是 {@code null}。登记进测试服每刻的等待。 */
    public static String busy() {
        int pending = LINKS.values().stream().mapToInt(link -> link.pipe.client().pending()).sum();
        return pending > 0 ? pending + " seat request(s) of a GameTest brain in flight" : null;
    }

    /** 每个服务器刻一次:座位上来的回调做掉,大脑往前走一步。 */
    public static void tickAll() {
        for (Link link : LINKS.values()) {
            for (Runnable task; (task = link.main.poll()) != null; ) {
                task.run();
            }
            link.brains.tick();
        }
    }

    /** 用完:大脑收掉,座位放掉,名册上的她除掉;这位主人最后一只收了,他的连接也断开。 */
    public void close() {
        link.sitting.remove(her.getUUID());
        link.brains.sit(Set.copyOf(link.sitting));
        if (link.sitting.isEmpty()) {
            link.brains.leave();
            link.pipe.disconnect();
            LINKS.remove(her.getOwnerUuid(), link);
        }
        CompanionRegistry.get(server).remove(her.getUUID());
    }
}
