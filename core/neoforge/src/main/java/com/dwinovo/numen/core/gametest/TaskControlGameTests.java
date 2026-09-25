package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskFactory;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.TimerRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 她手上在办的事:{@code numen task status} 查进度、{@code numen task stop} 叫停、{@code numen task timer} 定表。
 * 主人不在线,收尾与到点的事件进出箱,测试从那里读模型会收到的原话。
 *
 * <p>三个动作各自提升成了快捷工具({@code task_status} / {@code task_stop} / {@code set_timer}):同一件事从工具和
 * 从命令各调一次,回执与世界上的结果一样。
 *
 * <p>命令派下的长活叫什么、重启后怎么接回来,用夹具组 {@code gt_long} 验:它唯一的动作 {@code linger} 派一件站着
 * 数刻的后台活,并提升成快捷工具 {@code gt_linger}。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class TaskControlGameTests {

    private static final Param<Integer> TICKS = Param.required("ticks", ArgType.integer(1, 1200),
            "How long to stand, in ticks.");

    static {
        NumenPlugins.register(numen -> numen.registerCommands("gt_long",
                "Test fixture: long work dispatched by a command.", g ->
                        g.server("linger", "Stand still for a while, as background work.",
                                (src, args) -> TaskDispatch.setTask(src, new LingerRecord(src, args.get(TICKS))),
                                TICKS)
                                .example("numen gt_long linger 40")
                                .promote("gt_linger", "Stand still for a while, as background work.")));
        TaskFactory.register(LingerRecord.class, (body, record) -> new Linger(record));
    }

    /** 夹具的活:站着数够刻数就算干完。名字与调用 id 取自派它的那次调用。 */
    private static final class LingerRecord extends TaskRecord {
        final int ticks;

        LingerRecord(ServerSource source, int ticks) {
            super(source, source.companion().level().getGameTime() + ticks + 200);
            this.ticks = ticks;
        }
    }

    private static final class Linger implements Task {
        private final LingerRecord record;
        private int stood;

        Linger(LingerRecord record) {
            this.record = record;
        }

        @Override
        public TaskState tick(NumenPlayer companion) {
            return ++stood >= record.ticks ? TaskState.SUCCESS : TaskState.RUNNING;
        }

        @Override
        public void stop(NumenPlayer companion, StopReason why) {
        }

        @Override
        public TaskResult result(TaskState terminal) {
            return terminal == TaskState.SUCCESS ? TaskResult.ok("stood for " + stood + " ticks")
                    : TaskResult.fail("stopped after " + stood + " ticks");
        }

        @Override
        public String name() {
            return "linger";
        }
    }

    /** 任务控制批次前置:和平难度 + 正午。 */
    @BeforeBatch(batch = "numen_tasks")
    public static void prepareTasksBatch(ServerLevel level) {
        settleWorld(level, Difficulty.PEACEFUL, NOON);
    }

    /** 走在路上时查进度:报出这件活的编号和它是什么,也报挂着的表。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_status_names_the_running_task_and_the_timers(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_busy", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        ToolRun walk = call(companion, "goto", args("x", far.getX(), "y", far.getY(), "z", far.getZ()));
        ToolRun timer = command(companion, "numen task timer 600 check the furnace");
        ToolRun status = command(companion, "numen task status");

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.task() != null && timer.succeeded(), "goto or the timer did not go through: "
                    + walk.reply() + " / " + timer.reply());
            helper.assertTrue(status.succeeded() && status.reply().contains(walk.task().publicId())
                            && status.reply().contains("goto") && status.reply().contains("check the furnace"),
                    "task status does not name the walk and the timer: " + status.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 不带编号叫停:走到一半的 goto 停下,收尾以 status=stopped 的 task_finished 送到。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_tasks")
    public static void task_stop_without_an_id_stops_the_background_task(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_halted", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        ToolRun walk = call(companion, "goto", args("x", far.getX(), "y", far.getY(), "z", far.getZ()));
        AtomicReference<ToolRun> stop = new AtomicReference<>();
        EventOutbox outbox = EventOutbox.get(helper.getLevel().getServer());

        helper.startSequence()
                .thenExecuteAfter(5, () -> stop.set(command(companion, "numen task stop")))
                .thenWaitUntil(() -> helper.assertTrue(stop.get().succeeded()
                                && walk.task().getState() == TaskState.CANCELLED,
                        "the walk was not stopped: " + stop.get().reply()))
                .thenWaitUntil(() -> helper.assertTrue(outbox.peek(companion.getUUID()).entries().stream()
                                .anyMatch(e -> e.type().equals("task_finished") && e.text().contains("stopped")),
                        "no task_finished with status stopped went out: " + outbox.peek(companion.getUUID()).entries()))
                .thenExecute(() -> {
                    outbox.forget(companion.getUUID());
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 点名一个不存在的编号:不撤任何东西,回执把手上真有的摊开。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_stop_with_an_unknown_id_changes_nothing(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_confused", new BlockPos(2, 2, 2), false);
        ToolRun timer = command(companion, "numen task timer 600 feed the pets");
        ToolRun stop = command(companion, "numen task stop --task_id t9999");
        ToolRun status = command(companion, "numen task status");

        helper.succeedWhen(() -> {
            helper.assertTrue(timer.succeeded(), "the timer failed: " + timer.reply());
            helper.assertTrue(!stop.succeeded() && stop.reply().contains("feed the pets"),
                    "the refusal does not list what is pending: " + stop.reply());
            helper.assertTrue(status.reply().contains("feed the pets"), "the timer was cancelled: " + status.reply());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 一秒的表:到点发一条 timer 事件,带着定表时写的理由。 */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_tasks")
    public static void set_timer_fires_its_reason_back(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_alarmed", new BlockPos(2, 2, 2), false);
        ToolRun timer = command(companion, "numen task timer 1 the bread should be baked");
        EventOutbox outbox = EventOutbox.get(helper.getLevel().getServer());

        helper.succeedWhen(() -> {
            helper.assertTrue(timer.succeeded(), "the timer failed: " + timer.reply());
            helper.assertTrue(outbox.peek(companion.getUUID()).entries().stream()
                            .anyMatch(e -> e.type().equals("timer") && e.text().contains("the bread should be baked")),
                    "the timer has not fired with its reason: " + outbox.peek(companion.getUUID()).entries());
            outbox.forget(companion.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /** 同源:走在路上时,task_status 与 numen task status 在同一刻读到的是一字不差的同一份回执。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_status_reads_the_same_from_the_tool_and_the_command(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_twice_asked", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        ToolRun walk = call(companion, "goto", args("x", far.getX(), "y", far.getY(), "z", far.getZ()));
        ToolRun timer = call(companion, "set_timer", args("after_s", 600, "reason", "turn the compost"));
        AtomicReference<ToolRun> viaTool = new AtomicReference<>();
        AtomicReference<ToolRun> viaCommand = new AtomicReference<>();

        helper.startSequence()
                .thenExecuteAfter(3, () -> {
                    viaTool.set(call(companion, "task_status", args()));
                    viaCommand.set(command(companion, "numen task status"));
                })
                .thenExecute(() -> {
                    helper.assertTrue(walk.task() != null && timer.succeeded(), "setup failed: " + timer.reply());
                    helper.assertTrue(viaTool.get().succeeded()
                                    && viaTool.get().reply().contains(walk.task().publicId())
                                    && viaTool.get().reply().contains("turn the compost"),
                            "task_status does not name the walk and the timer: " + viaTool.get().reply());
                    helper.assertTrue(viaTool.get().reply().equals(viaCommand.get().reply()),
                            "the tool and the command read differently: " + viaTool.get().reply()
                                    + " / " + viaCommand.get().reply());
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 同源:点名不存在的编号,工具与命令的拒绝一字不差,表都还在。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_stop_refuses_the_same_from_the_tool_and_the_command(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_twice_refused", new BlockPos(2, 2, 2), false);
        ToolRun timer = command(companion, "numen task timer 600 air out the cellar");
        ToolRun viaTool = call(companion, "task_stop", args("task_id", "t9999"));
        ToolRun viaCommand = command(companion, "numen task stop --task_id t9999");

        helper.succeedWhen(() -> {
            helper.assertTrue(timer.succeeded(), "the timer failed: " + timer.reply());
            helper.assertTrue(!viaTool.succeeded() && viaTool.reply().contains("air out the cellar"),
                    "the refusal does not list what is pending: " + viaTool.reply());
            helper.assertTrue(viaTool.reply().equals(viaCommand.reply()),
                    "the tool and the command refuse differently: " + viaTool.reply() + " / " + viaCommand.reply());
            helper.assertTrue(TimerRegistry.get(helper.getLevel().getServer()).list(companion.getUUID()).size() == 1,
                    "a refused stop removed the timer");
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 同源:一个经 set_timer、一个经 numen task timer,在同一刻定同样的表——回执除了各自的表编号一字不差,
     * 世界上各多一个到期时刻与理由都相同的表。越界的秒数两边都夹住并说明。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void set_timer_from_the_tool_and_the_command_sets_the_same_timer(GameTestHelper helper) {
        NumenPlayer viaToolBody = spawnAt(helper, "gametest_tool_timer", new BlockPos(2, 2, 2), false);
        NumenPlayer viaCommandBody = spawnAt(helper, "gametest_cmd_timer", new BlockPos(6, 2, 2), false);
        ToolRun viaTool = call(viaToolBody, "set_timer", args("after_s", 5000, "reason", "water the wheat"));
        ToolRun viaCommand = command(viaCommandBody, "numen task timer 5000 water the wheat");

        helper.succeedWhen(() -> {
            helper.assertTrue(viaTool.succeeded() && viaCommand.succeeded(),
                    "a timer failed: " + viaTool.reply() + " / " + viaCommand.reply());
            helper.assertTrue(viaTool.reply().contains("你要 5000s"), "the clamp is not explained: " + viaTool.reply());
            helper.assertTrue(withoutTimerId(viaTool.reply()).equals(withoutTimerId(viaCommand.reply())),
                    "the tool and the command answer differently: " + viaTool.reply() + " / " + viaCommand.reply());
            TimerRegistry timers = TimerRegistry.get(helper.getLevel().getServer());
            List<TimerRegistry.Timer> a = timers.list(viaToolBody.getUUID());
            List<TimerRegistry.Timer> b = timers.list(viaCommandBody.getUUID());
            helper.assertTrue(a.size() == 1 && b.size() == 1
                            && a.get(0).dueGameTime() == b.get(0).dueGameTime()
                            && a.get(0).reason().equals(b.get(0).reason()),
                    "the two timers differ: " + a + " / " + b);
            CompanionFactory.despawn(helper.getLevel().getServer(), viaToolBody);
            CompanionFactory.despawn(helper.getLevel().getServer(), viaCommandBody);
        });
    }

    /**
     * 命令派下的长活叫"组 动作",快捷工具派下的叫快捷工具名:受理回执、任务记录、task_finished 三处都是这个名字,
     * 而重启要重放的仍是那次调用本身(command 与那一行指令)。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_long_command_is_named_after_its_group_and_action(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer viaCommandBody = Companions.summon(server, UUID.randomUUID(), "gametest_lingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        NumenPlayer viaToolBody = spawnAt(helper, "gametest_tool_lingerer", new BlockPos(6, 2, 2), false);
        ToolRun viaCommand = command(viaCommandBody, "numen gt_long linger 20");
        ToolRun viaTool = call(viaToolBody, "gt_linger", args("ticks", 20));
        CompanionRegistry.Entry recorded = CompanionRegistry.get(server).find(viaCommandBody.getUUID());
        EventOutbox outbox = EventOutbox.get(server);

        helper.succeedWhen(() -> {
            helper.assertTrue(taskIn(viaCommand.reply()).equals("gt_long linger")
                            && viaCommand.task().getToolName().equals("gt_long linger"),
                    "the command's task is not named group + action: " + viaCommand.reply());
            helper.assertTrue(taskIn(viaTool.reply()).equals("gt_linger")
                            && viaTool.task().getToolName().equals("gt_linger"),
                    "the shortcut's task is not named after the shortcut: " + viaTool.reply());
            helper.assertTrue(recorded.taskTool().equals(com.dwinovo.numen.cli.CommandTool.NAME)
                            && recorded.taskArgs().contains("numen gt_long linger 20"),
                    "the replay recipe is not the call itself: " + recorded.taskTool() + " " + recorded.taskArgs());
            helper.assertTrue(recorded.taskName().equals("gt_long linger"),
                    "the task is recorded under another name: " + recorded.taskName());
            helper.assertTrue(finishedAs(outbox, viaCommandBody, "gt_long linger")
                            && finishedAs(outbox, viaToolBody, "gt_linger"),
                    "task_finished does not name the task: " + outbox.peek(viaCommandBody.getUUID()).entries()
                            + " / " + outbox.peek(viaToolBody.getUUID()).entries());
            outbox.forget(viaCommandBody.getUUID());
            outbox.forget(viaToolBody.getUUID());
            Companions.dismiss(server, viaCommandBody);
            CompanionFactory.despawn(server, viaToolBody);
        });
    }

    /**
     * 重启后接回命令派下的长活:重放那一行命令,接回来的活照样叫"组 动作",收尾的 task_finished 也是这个名字。
     * 重启用"休眠 + 把重启前落盘的那条记录放回去 + 复活"来演。
     */
    @GameTest(template = "floor16", timeoutTicks = 400, batch = "numen_tasks")
    public static void a_restored_long_command_keeps_its_name(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = Companions.summon(server, UUID.randomUUID(), "gametest_relingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun before = command(first, "numen gt_long linger 1000");
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry recorded = registry.find(uuid);
        Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskTool(), recorded.taskArgs()));
        NumenPlayer second = Companions.respawn(server, uuid);
        helper.assertTrue(second != null, "the body was not rebuilt");
        EventOutbox outbox = EventOutbox.get(server);
        AtomicReference<TaskRecord> restored = new AtomicReference<>();

        helper.startSequence()
                .thenWaitUntil(() -> {
                    helper.assertTrue(before.task() != null, "the first dispatch failed: " + before.reply());
                    TaskRecord now = CompanionTickDispatcher.currentTaskFor(uuid);
                    helper.assertTrue(now != null && now != before.task(), "the task was not replayed");
                    restored.set(now);
                })
                .thenWaitUntil(() -> helper.assertTrue(restored.get().getToolName().equals("gt_long linger"),
                        "the replayed task is named " + restored.get().getToolName()))
                .thenExecute(() -> CompanionTickDispatcher.stopActive(second, TaskRecord.StopCause.TASK_STOP))
                .thenWaitUntil(() -> helper.assertTrue(outbox.peek(uuid).entries().stream()
                                .anyMatch(e -> e.type().equals("task_finished")
                                        && e.text().contains("task=\"gt_long linger\"")
                                        && e.text().contains(restored.get().publicId())),
                        "the replayed task did not finish under its name: " + outbox.peek(uuid).entries()))
                .thenExecute(() -> {
                    outbox.forget(uuid);
                    Companions.dismiss(server, second);
                })
                .thenSucceed();
    }

    /**
     * 重启后接不回来的命令长活:重放那一行被拒(这里把落盘的那一行改成写不通的),她收到的 task_finished 仍以受理时的
     * 名字"组 动作"说这件活没接回来,不是重放用的工具名 command。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void an_abandoned_long_command_is_reported_under_its_name(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = Companions.summon(server, UUID.randomUUID(), "gametest_unlingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun before = command(first, "numen gt_long linger 1000");
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry recorded = registry.find(uuid);
        Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskTool(),
                "{\"command\":\"numen gt_long linger soon\"}"));
        NumenPlayer second = Companions.respawn(server, uuid);
        helper.assertTrue(second != null, "the body was not rebuilt");
        EventOutbox outbox = EventOutbox.get(server);

        helper.succeedWhen(() -> {
            helper.assertTrue(before.task() != null, "the first dispatch failed: " + before.reply());
            helper.assertTrue(registry.find(uuid).taskTool().isBlank(),
                    "the task that cannot be replayed is still on record");
            helper.assertTrue(outbox.peek(uuid).entries().stream()
                            .anyMatch(e -> e.type().equals("task_finished")
                                    && e.text().contains("task=\"gt_long linger\"")
                                    && e.text().contains("status=\"failed\"")
                                    && e.text().contains("没能接回来")),
                    "she was not told under the task's name: " + outbox.peek(uuid).entries());
            outbox.forget(uuid);
            Companions.dismiss(server, second);
        });
    }

    private static String taskIn(String reply) {
        return JsonParser.parseString(reply).getAsJsonObject().getAsJsonObject("data").get("task").getAsString();
    }

    private static boolean finishedAs(EventOutbox outbox, NumenPlayer body, String task) {
        return outbox.peek(body.getUUID()).entries().stream().anyMatch(e -> e.type().equals("task_finished")
                && e.text().contains("task=\"" + task + "\"") && e.text().contains("status=\"done\""));
    }

    /** 回执里只有表编号因人而异(全服一个计数器),把它抹成同一个记号再比。 */
    private static String withoutTimerId(String reply) {
        JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
        String id = json.getAsJsonObject("data").get("timer_id").getAsString();
        return reply.replace(id, "tm?");
    }
}
