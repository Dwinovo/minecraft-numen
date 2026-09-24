package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.TimerRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Difficulty;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static com.dwinovo.numen.core.gametest.GameTestKit.*;

/**
 * 她手上在办的事:{@code numen task status} 查进度、{@code numen task stop} 叫停、{@code numen task timer} 定表。
 * 主人不在线,收尾与到点的事件进出箱,测试从那里读模型会收到的原话。
 *
 * <p>三个动作各自提升成了快捷工具({@code task_status} / {@code task_stop} / {@code set_timer}):同一件事从工具和
 * 从命令各调一次,回执与世界上的结果一样。
 */
@GameTestHolder(Constants.MOD_ID)
@PrefixGameTestTemplate(false)
public class TaskControlGameTests {

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

    /** 回执里只有表编号因人而异(全服一个计数器),把它抹成同一个记号再比。 */
    private static String withoutTimerId(String reply) {
        JsonObject json = JsonParser.parseString(reply).getAsJsonObject();
        String id = json.getAsJsonObject("data").get("timer_id").getAsString();
        return reply.replace(id, "tm?");
    }
}
