package com.dwinovo.numen.gametest;

import com.dwinovo.numen.api.gametest.Checks;
import com.dwinovo.numen.api.gametest.Bodies;
import com.dwinovo.numen.api.agent.provider.LlmToolCall;
import com.dwinovo.numen.api.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.Constants;
import com.dwinovo.numen.api.entity.CompanionFactory;
import com.dwinovo.numen.api.entity.CompanionRegistry;
import com.dwinovo.numen.api.entity.Companions;
import com.dwinovo.numen.api.agent.inbox.EventQueue;
import com.dwinovo.numen.api.brain.CompanionLoops;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Job;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.CompanionTickDispatcher;
import com.dwinovo.numen.api.task.Task;
import com.dwinovo.numen.api.task.TaskFactory;
import com.dwinovo.numen.api.task.TaskRecord;
import com.dwinovo.numen.api.task.TaskResult;
import com.dwinovo.numen.api.task.TaskState;
import com.dwinovo.numen.api.task.TimerRegistry;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import com.dwinovo.numen.api.gametest.BeforeBatch;
import com.dwinovo.numen.api.gametest.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.gametest.GameTestKit.*;

/**
 * 她手上在办的事:{@code numen.task.status} 查进度、{@code numen.task.stop} 叫停、{@code numen.task.timer} 定表。
 * 收尾与到点的事件进她的收件箱,测试从那里读模型会收到的原话。
 *
 * <p>只有 {@code numen.task.stop} 提升成了快捷工具 {@code task_stop}:同一件事从工具和从命令各调一次,回执与世界上的结果
 * 一样。{@code numen.task.status}、{@code numen.task.timer} 只作命令,工具表里没有它们。
 *
 * <p>函数派下的长活叫什么、重启后怎么接回来(记下的那一行 Lua 再跑一遍),用夹具组 {@code gt_long} 验:它唯一的函数
 * {@code linger} 派一件站着数刻的后台活。
 *
 * <p>身体同时只做一件后台活:模型一次回复里的几条调用经 {@link GameTestKit#round} 按内脑派发的同一个顺序执行(一件做完
 * 才派下一件,等的时候主人开口余下的不再执行);下一轮派的替换正在做的,受理回执说顶掉了谁。
 */
public class TaskControlGameTests {

    static {
        if (GameTestKit.numenTestsEnabled()) {
            NumenPlugins.register("gt", numen -> numen.api("gt_long", "Test fixture: long work.", LongWork.class));
            TaskFactory.register(LingerRecord.class, (body, record) -> new Linger(record));
        }
    }

    /** 夹具组 {@code gt.gt_long}:派一件站着数刻的后台活。 */
    public static final class LongWork {

        private LongWork() {}

        /** 站多久。 */
        public record Ticks(@Doc("How long to stand, in ticks (1-1200).") int ticks) {}

        @Fn("Stand still for a while, as background work.")
        @Example("gt.gt_long.linger(40)")
        public static Job<Void> linger(ServerCall call, Ticks args) {
            return Job.of(new LingerRecord(call, Math.clamp(args.ticks(), 1, 1200)));
        }
    }

    /** 夹具的活:站着数够刻数就算干完。名字与调用 id 取自派它的那次调用。 */
    private static final class LingerRecord extends TaskRecord<Void> {
        final int ticks;

        LingerRecord(ServerCall call, int ticks) {
            super(call, call.her().level().getGameTime() + ticks + 200);
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
        ToolRun walk = lua(companion, "numen.move.walk(" + xyz(far) + ")");
        AtomicReference<ToolRun> timer = new AtomicReference<>();
        AtomicReference<ToolRun> status = new AtomicReference<>();

        // goto 规划过、受理了才在走:那之后再定表(她手上的程序一次只有一段,走路那一段停在调用之间,活照常跑)、再查
        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.accepted(), "goto did not go through: " + walk.reply()))
                .thenExecute(() -> timer.set(lua(companion, "numen.task.timer(\"check the furnace\", {after = 600})")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, timer.get().succeeded(),
                        "the timer did not go through: " + timer.get().reply()))
                .thenExecute(() -> status.set(lua(companion, "numen.task.status()")))
                .thenExecute(() -> {
                    Checks.assertTrue(helper, status.get().succeeded()
                                    && status.get().reply().contains(walk.task().publicId())
                                    && status.get().reply().contains("numen.move.walk")
                                    && status.get().reply().contains("check the furnace"),
                            "task status does not name the walk and the timer: " + status.get().reply());
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 不带编号叫停:走到一半的 goto 停下,收尾以 status=stopped 的 task_finished 送到。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_stop_without_an_id_stops_the_background_task(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_halted", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        ToolRun walk = lua(companion, "numen.move.walk(" + xyz(far) + ")");
        AtomicReference<ToolRun> stop = new AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.accepted(), "goto has not been accepted: " + walk.reply()))
                .thenExecuteAfter(5, () -> stop.set(lua(companion, "numen.task.stop()")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, stop.get().succeeded()
                                && walk.task().getState() == TaskState.CANCELLED,
                        "the walk was not stopped: " + stop.get().reply()))
                .thenWaitUntil(() -> Checks.assertTrue(helper, GameTestKit.events(companion).stream()
                                .anyMatch(e -> e.type().equals("task_finished") && e.text().contains("stopped")),
                        "no task_finished with status stopped went out: " + GameTestKit.events(companion)))
                .thenExecute(() -> {
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 一秒的表:到点发一条 timer 事件,带着定表时写的理由。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void set_timer_fires_its_reason_back(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_alarmed", new BlockPos(2, 2, 2), false);
        ToolRun timer = lua(companion, "numen.task.timer(\"the bread should be baked\", {after = 1})");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, timer.succeeded(), "the timer failed: " + timer.reply());
            Checks.assertTrue(helper, GameTestKit.events(companion).stream()
                            .anyMatch(e -> e.type().equals("timer") && e.text().contains("the bread should be baked")),
                    "the timer has not fired with its reason: " + GameTestKit.events(companion));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 查、定表、叫停都只是 {@code task} 组的函数:工具表里只有跑程序的那一个,没有 task_status、set_timer、task_stop。
     * 走在路上时 numen.task.status 照样报出这件活与挂着的表;点名不存在的编号叫停,拒绝里列出还挂着的表,表都还在。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void task_status_and_stop_are_commands_only(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_twice_asked", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(14, 2, 14));
        ToolRun walk = lua(companion, "numen.move.walk(" + xyz(far) + ")");
        ToolRun timer = lua(companion, "numen.task.timer(\"turn the compost\", {after = 600})");
        AtomicReference<ToolRun> status = new AtomicReference<>();
        NumenPlayer refused = spawnAt(helper, "gametest_twice_refused", new BlockPos(6, 2, 2), false);
        ToolRun cellarTimer = lua(refused, "numen.task.timer(\"air out the cellar\", {after = 600})");
        ToolRun viaTool = lua(refused, "numen.task.stop({task_id = \"t9999\"})");
        ToolRun viaCommand = lua(refused, "numen.task.stop({task_id = \"t9999\"})");

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.accepted(), "goto has not been accepted: " + walk.reply()))
                .thenExecuteAfter(3, () -> status.set(lua(companion, "numen.task.status()")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, cellarTimer.done() && viaTool.done() && viaCommand.done(),
                        "the timer and the refused stops have not answered"))
                .thenExecute(() -> {
                    Checks.assertTrue(helper, ToolRegistry.get("task_status") == null && ToolRegistry.get("set_timer") == null
                                    && ToolRegistry.get("task_stop") == null,
                            "task_status, set_timer or task_stop is still a tool");
                    Checks.assertTrue(helper, ToolRegistry.get(com.dwinovo.numen.script.ScriptEngine.IN_USE.toolName())
                            != null, "the lua tool is missing");
                    Checks.assertTrue(helper, walk.task() != null && timer.succeeded(), "setup failed: " + timer.reply());
                    Checks.assertTrue(helper, status.get().succeeded()
                                    && status.get().reply().contains(walk.task().publicId())
                                    && status.get().reply().contains("turn the compost"),
                            "task status does not name the walk and the timer: " + status.get().reply());
                    Checks.assertTrue(helper, cellarTimer.succeeded(), "the timer failed: " + cellarTimer.reply());
                    Checks.assertTrue(helper, !viaTool.succeeded() && viaTool.reply().contains("air out the cellar"),
                            "the refusal does not list what is pending: " + viaTool.reply());
                    Checks.assertTrue(helper, viaTool.reply().equals(viaCommand.reply()),
                            "the tool and the command refuse differently: " + viaTool.reply() + " / "
                                    + viaCommand.reply());
                    Checks.assertTrue(helper, TimerRegistry.get(helper.getLevel().getServer()).list(refused.getUUID()).size() == 1,
                            "a refused stop removed the timer");
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                    CompanionFactory.despawn(helper.getLevel().getServer(), refused);
                })
                .thenSucceed();
    }

    /**
     * 脚本里的函数派下的长活叫那个函数名:任务记录、task_finished 都是这个名字,而重启要再跑的是那次调用写成的一行 Lua。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_long_action_is_named_after_its_function(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer body = Bodies.summon(server, UUID.randomUUID(), "gametest_lingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        ToolRun linger = lua(body, "gt.gt_long.linger(20)");
        CompanionRegistry.Entry recorded = CompanionRegistry.get(server).find(body.getUUID());

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, linger.task() != null && linger.task().getToolName().equals("gt.gt_long.linger")
                            && linger.reply().contains("\"job\":\"" + linger.task().publicId() + "\""),
                    "the task is not named after its function: " + linger.reply());
            Checks.assertTrue(helper, recorded.taskLua().equals("gt.gt_long.linger(20)"),
                    "the replayed line is not the call itself: " + recorded.taskLua());
            Checks.assertTrue(helper, recorded.taskName().equals("gt.gt_long.linger"),
                    "the task is recorded under another name: " + recorded.taskName());
            // 程序等着的这件活的收尾写在它的回执里,不另发事件
            Checks.assertTrue(helper, linger.receipt() != null && linger.receipt().contains("line 1 gt.gt_long.linger: stood for 20 ticks"),
                    "the receipt does not name the task's end: " + linger.receipt());
            Companions.dismiss(server, body);
        });
    }

    /**
     * 换驱动者:站着的长活被一件短活顶掉(任务自己的 stop 什么也不松),大脑统一把旧驱动者按着的键全松、鼠标的挖掘进度清零,
     * 新驱动者拿到的是一具干净的身体。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_new_driver_starts_from_a_clean_body(GameTestHelper helper) {
        BlockPos stone = helper.absolutePos(new BlockPos(5, 2, 5));
        helper.getLevel().setBlockAndUpdate(stone, Blocks.STONE.defaultBlockState());
        NumenPlayer companion = spawnAt(helper, "gametest_clean_hands", new BlockPos(3, 2, 5), false);
        ToolRun linger = lua(companion, "gt.gt_long.linger(100)");
        AtomicReference<ToolRun> hold = new AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, linger.task() != null, "the long work was not accepted: " + linger.reply()))
                .thenExecute(() -> {
                    companion.look().at(Vec3.atCenterOf(stone));
                    companion.controls().press(com.dwinovo.numen.api.entity.Controls.Key.FORWARD);
                    companion.controls().press(com.dwinovo.numen.api.entity.Controls.Key.SNEAK);
                    companion.mouse().dig();
                    Checks.assertTrue(helper, companion.mouse().digging(), "the left button did not go down on the stone");
                    hold.set(lua(companion, "gt.gt_sync.hold(3)"));
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, hold.get().done() && hold.get().succeeded()
                                && !companion.controls().held(com.dwinovo.numen.api.entity.Controls.Key.FORWARD)
                                && !companion.controls().held(com.dwinovo.numen.api.entity.Controls.Key.SNEAK)
                                && !companion.mouse().digging(),
                        "the short work took the body but the old driver's keys are still down: " + hold.get().outcome()))
                .thenExecute(() -> CompanionFactory.despawn(helper.getLevel().getServer(), companion))
                .thenSucceed();
    }

    /**
     * 重启后接回函数派下的长活:再跑记下的那一行 Lua,接回来的活照样叫那个函数名,收尾的 task_finished 也是这个名字。
     * 重启用"休眠 + 把重启前落盘的那条记录放回去 + 复活"来演。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_restored_long_command_keeps_its_name(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = Bodies.summon(server, UUID.randomUUID(), "gametest_relingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun before = lua(first, "gt.gt_long.linger(1000)");
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry recorded = registry.find(uuid);
        Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskLua()));
        NumenPlayer second = Bodies.respawn(server, uuid);
        Checks.assertTrue(helper, second != null, "the body was not rebuilt");
        EventQueue inbox = CompanionLoops.get(uuid).orElseThrow().inbox();   // 她有服务端大脑:事件进她的收件箱
        AtomicReference<TaskRecord<?>> restored = new AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> {
                    Checks.assertTrue(helper, before.task() != null, "the first dispatch failed: " + before.reply());
                    TaskRecord<?> now = CompanionTickDispatcher.currentTaskFor(uuid);
                    Checks.assertTrue(helper, now != null && now != before.task(), "the task was not replayed");
                    restored.set(now);
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, restored.get().getToolName().equals("gt.gt_long.linger"),
                        "the replayed task is named " + restored.get().getToolName()))
                .thenExecute(() -> CompanionTickDispatcher.stopActive(second, TaskRecord.StopCause.TASK_STOP))
                .thenWaitUntil(() -> Checks.assertTrue(helper, inbox.entries().stream()
                                .anyMatch(e -> e.type().equals("task_finished")
                                        && e.text().contains("task=\"gt.gt_long.linger\"")
                                        && e.text().contains(restored.get().publicId())),
                        "the replayed task did not finish under its name: " + inbox.entries()))
                .thenExecute(() -> {
                    Companions.dismiss(server, second);
                })
                .thenSucceed();
    }

    /**
     * 重启后接不回来的长活:再跑那一行失败(这里把落盘的那一行改成读不通的),她收到的 task_finished 仍以受理时的
     * 名字(那个函数名)说这件活没接回来。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void an_abandoned_long_command_is_reported_under_its_name(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = Bodies.summon(server, UUID.randomUUID(), "gametest_unlingerer", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun before = lua(first, "gt.gt_long.linger(1000)");
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry recorded = registry.find(uuid);
        Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), "gt.gt_long.linger(\"soon\")"));
        NumenPlayer second = Bodies.respawn(server, uuid);
        Checks.assertTrue(helper, second != null, "the body was not rebuilt");
        EventQueue inbox = CompanionLoops.get(uuid).orElseThrow().inbox();   // 她有服务端大脑:事件进她的收件箱

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, before.task() != null, "the first dispatch failed: " + before.reply());
            Checks.assertTrue(helper, registry.find(uuid).taskName().isBlank(),
                    "the task that cannot be replayed is still on record");
            Checks.assertTrue(helper, inbox.entries().stream()
                            .anyMatch(e -> e.type().equals("task_finished")
                                    && e.text().contains("task=\"gt.gt_long.linger\"")
                                    && e.text().contains("status=\"failed\"")
                                    && e.text().contains("could not be resumed after the restart")
                                    && e.text().contains("bad_argument")),
                    "she was not told under the task's name: " + inbox.entries());
            Companions.dismiss(server, second);
        });
    }

    /**
     * 身体刚进世界、调度器还没 tick 过的那一刻派下的长活(调用唤醒休眠的她就是这样)不是重启前留下的:它照常跑、
     * 照常落盘,不被当成旧活再跑后失败,也没有一条假的"没接回来"。重启前留下的那件被它顶替,她收到一条
     * task_finished 说明。重启用"休眠 + 把重启前落盘的那条记录放回去 + 复活"来演。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_task_dispatched_before_the_first_tick_is_not_taken_for_a_left_over(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        NumenPlayer first = Bodies.summon(server, UUID.randomUUID(), "gametest_woken", level,
                new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5));
        UUID uuid = first.getUUID();
        ToolRun before = lua(first, "gt.gt_long.linger(1000)");
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry recorded = registry.find(uuid);
        Companions.dormant(server, first);
        registry.put(uuid, registry.find(uuid).doing(recorded.taskName(), recorded.taskLua()));
        EventQueue inbox = CompanionLoops.get(uuid).orElseThrow().inbox();   // 她有服务端大脑:事件进她的收件箱
        inbox.takeEntries(System.currentTimeMillis(), 0L);   // 先清掉第一次派活留下的
        NumenPlayer second = Bodies.respawn(server, uuid);
        Checks.assertTrue(helper, second != null, "the body was not rebuilt");
        ToolRun woken = lua(second, "gt.gt_long.linger(900)");

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, before.task() != null, "the first dispatch failed: " + before.reply());
            Checks.assertTrue(helper, woken.task() != null, "the new dispatch was refused: " + woken.reply());
            Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(uuid) == woken.task(),
                    "the new task is not the one running: " + CompanionTickDispatcher.currentTaskFor(uuid));
            Checks.assertTrue(helper, registry.find(uuid).taskLua().equals("gt.gt_long.linger(900)"),
                    "the new task is not on record: " + registry.find(uuid).taskLua());
            var entries = inbox.entries();
            Checks.assertTrue(helper, entries.stream().noneMatch(e -> e.text().contains("could not be resumed")),
                    "a task was reported as not taken back: " + entries);
            Checks.assertTrue(helper, entries.stream().anyMatch(e -> e.type().equals("task_finished")
                            && e.text().contains("task=\"gt.gt_long.linger\"")
                            && e.text().contains("status=\"stopped\"")
                            && e.text().contains("a newer one replaced it")),
                    "she was not told the left-over task was superseded: " + entries);
            Companions.dismiss(server, second);
        });
    }

    // ---- 一轮里的几条调用:一件做完才派下一件;下一轮派的替换正在做的 ----

    /** 一轮里 goto 之后接一个只读的 numen.status.self:它等她走到了才执行,读到的是到达之后的她。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_query_after_a_goto_in_one_round_runs_on_arrival(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_arriver", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        LlmToolCall walk = programCall("numen.move.walk({x = " + far.getX() + ", z = " + far.getZ() + "})");
        LlmToolCall look = programCall("return numen.status.self()");
        Round round = round(helper, companion, walk, look);

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, round.hasSettled(), "the round has not settled");
            Vec3 at = round.startedAt(look);
            double off = Math.hypot(at.x - (far.getX() + 0.5), at.z - (far.getZ() + 0.5));
            Checks.assertTrue(helper, off < 1.5, "status self ran " + off + " blocks from where the walk ends");
            Checks.assertTrue(helper, round.result(look).contains("pos = {"), "status self did not answer: "
                    + round.result(look));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 跟随总有收尾:{@code seconds} 到了以成功收场,同一轮后面的调用接着执行;跟着的这段时间里它是她手上的活,收场后手上空了。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_follow_ends_on_time_and_the_round_goes_on(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_tagalong", new BlockPos(2, 2, 2), false);
        LlmToolCall follow = programCall("numen.move.follow({seconds = 2})");
        LlmToolCall look = programCall("return numen.status.self()");
        Round round = round(helper, companion, follow, look);
        boolean[] following = new boolean[1];
        helper.onEachTick(() -> {
            TaskRecord<?> now = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
            following[0] |= now != null && now.getToolName().equals("numen.move.follow");
        });

        succeedWhen(helper, () -> {
            Checks.assertTrue(helper, following[0], "following was never what she was doing");
            Checks.assertTrue(helper, round.hasSettled() && round.result(look) != null,
                    "the call after the follow is still waiting: " + round.result(follow));
            Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(companion.getUUID()) == null,
                    "the follow did not end on time: " + CompanionTickDispatcher.currentTaskFor(companion.getUUID()));
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }

    /**
     * 等她走到的时候主人开口:同一轮里还没派的两条不再执行,各回一条"没执行"并写明原因,这一轮结算;在走的那段路照常走。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void the_owner_speaking_while_she_walks_leaves_the_rest_unrun(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_interrupted", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        LlmToolCall walk = programCall("numen.move.walk({x = " + far.getX() + ", z = " + far.getZ() + "})");
        LlmToolCall look = programCall("return numen.status.self()");
        LlmToolCall around = programCall("numen.scan.map()");
        Round round = round(helper, companion, walk, look, around);

        steps(helper)
                .thenExecuteAfter(5, () -> {
                    Checks.assertTrue(helper, !round.hasSettled() && round.result(look) == null,
                            "the query did not wait for the walk: " + round.result(look));
                    round.ownerSays("wait, come back");
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, round.hasSettled(), "the round did not settle when the owner spoke"))
                .thenExecute(() -> {
                    for (LlmToolCall skipped : List.of(look, around)) {
                        String result = round.result(skipped);
                        Checks.assertTrue(helper, result != null && result.contains("Not run")
                                        && result.contains("your owner spoke") && result.contains("keeps running"),
                                "a call left unrun does not say why: " + result);
                    }
                    TaskRecord<?> now = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
                    Checks.assertTrue(helper, now != null && now.getToolName().equals("numen.move.walk"),
                            "the walk is no longer running: " + now);
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /** 下一轮派新的身体动作:它替换正在走的那段路;被顶掉的照常以 stopped 收尾,她从那条 task_finished 知道。 */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_body_action_in_a_later_round_replaces_the_job_in_hand(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_changed_mind", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(13, 2, 13));
        BlockPos near = helper.absolutePos(new BlockPos(2, 2, 8));
        ToolRun walk = lua(companion, "numen.move.walk({x = " + far.getX() + ", z = " + far.getZ() + "})");
        AtomicReference<ToolRun> instead = new AtomicReference<>();

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.accepted(), "the first walk has not been accepted: "
                        + walk.reply()))
                .thenExecuteAfter(3, () -> instead.set(lua(companion, "numen.move.walk({x = " + near.getX() + ", z = " + near.getZ() + "})")))
                .thenWaitUntil(() -> {
                    Checks.assertTrue(helper, instead.get().accepted(), "the second walk has not been accepted");
                    Checks.assertTrue(helper, walk.task().getState() == TaskState.CANCELLED,
                            "the first walk was not stopped: " + walk.task().getState());
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, GameTestKit.events(companion).stream()
                                .anyMatch(e -> e.type().equals("task_finished")
                                        && e.text().contains("id=\"" + walk.task().publicId() + "\"")
                                        && e.text().contains("status=\"stopped\"")),
                        "the replaced walk did not wind down as stopped: " + GameTestKit.events(companion)))
                .thenExecute(() -> {
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    /**
     * 受理 = 这件活此刻真能开始。先走一段路,受理回执带着任务编号。到了之后她跟着主人(主人不在线,这件活睡着占着槽,到点才收);
     * 这时派一趟当场就走不了的 {@code numen.move.walk}——目标是一只不存在的实体。直接回错误:说那只实体不在,没有任务编号,不发
     * task_finished;她手上的跟随还是那一件,照旧在跑。
     */
    @GameTest(template = "floor16", timeoutTicks = 200, batch = "numen_tasks")
    public static void a_walk_that_cannot_start_is_refused_and_the_work_in_hand_goes_on(GameTestHelper helper) {
        NumenPlayer companion = spawnAt(helper, "gametest_undeterred", new BlockPos(2, 2, 2), false);
        BlockPos far = helper.absolutePos(new BlockPos(2, 2, 14));
        ToolRun walk = lua(companion, "numen.move.walk(" + xyz(far) + ")");
        AtomicReference<ToolRun> follow = new AtomicReference<>();
        AtomicReference<ToolRun> blocked = new AtomicReference<>();
        String absent = absentCode(companion.getServer());

        steps(helper)
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.reply() != null, "the first walk has not replied"))
                .thenExecute(() -> {
                    Checks.assertTrue(helper, walk.accepted(), "the first walk was not accepted: " + walk.reply());
                    String reply = walk.reply();
                    Constants.LOG.info("[numen-task] numen.move.walk accepted -> {}", reply);
                    Checks.assertTrue(helper, reply.contains("\"job\":\"" + walk.task().publicId() + "\""),
                            "the walk was not accepted with its task id: " + reply);
                })
                .thenWaitUntil(() -> Checks.assertTrue(helper, walk.done() && walk.succeeded(),
                        "the first walk did not arrive: " + walk.outcome()))
                .thenExecute(() -> follow.set(lua(companion, "numen.move.follow()")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, follow.get().accepted(),
                        "following was not accepted: " + follow.get().reply()))
                .thenExecute(() -> blocked.set(lua(companion, "numen.move.walk(\"pig@" + absent + "\")")))
                .thenWaitUntil(() -> Checks.assertTrue(helper, blocked.get().done(), "the blocked walk has not replied"))
                .thenExecute(() -> {
                    ToolRun refused = blocked.get();
                    Checks.assertTrue(helper, refused.refused(), "a walk to nothing was accepted: " + refused.reply());
                    String reply = refused.outcome();
                    Constants.LOG.info("[numen-task] numen.move.walk refused -> {}", reply);
                    Checks.assertTrue(helper, reply.contains("pig@" + absent + " is not near you") && !reply.contains("task_id"),
                            "the refusal does not say why, or carries a task id: " + reply);
                    TaskRecord<?> inHand = CompanionTickDispatcher.currentTaskFor(companion.getUUID());
                    Checks.assertTrue(helper, inHand == follow.get().task() && inHand.getState() == TaskState.RUNNING,
                            "the refused call interrupted the work in hand: " + inHand);
                })
                .thenExecuteAfter(10, () -> {
                    // 收件箱里只有那段路自己的收尾:被拒的调用没有任务编号,也就没有 task_finished
                    Checks.assertTrue(helper, GameTestKit.events(companion).stream()
                                    .filter(e -> e.type().equals("task_finished"))
                                    .allMatch(e -> e.text().contains("id=\"" + walk.task().publicId() + "\"")
                                            && e.text().contains("status=\"done\"")),
                            "a refused call sent task_finished: " + GameTestKit.events(companion));
                    Checks.assertTrue(helper, CompanionTickDispatcher.currentTaskFor(companion.getUUID()) == follow.get().task()
                                    && follow.get().task().getState() == TaskState.RUNNING,
                            "the work in hand stopped after the refusal");
                    CompanionFactory.despawn(helper.getLevel().getServer(), companion);
                })
                .thenSucceed();
    }

    private static boolean finishedAs(NumenPlayer body, String task) {
        return GameTestKit.events(body).stream().anyMatch(e -> e.type().equals("task_finished")
                && e.text().contains("task=\"" + task + "\"") && e.text().contains("status=\"done\""));
    }
}
