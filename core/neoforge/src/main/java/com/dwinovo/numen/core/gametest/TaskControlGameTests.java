package com.dwinovo.numen.core.gametest;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.EventOutbox;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
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
 * 她手上在办的事:{@code task_status} 查进度、{@code task_stop} 叫停、{@code set_timer} 定表。主人不在线,
 * 收尾与到点的事件进出箱,测试从那里读模型会收到的原话。
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
        ToolRun timer = call(companion, "set_timer", args("after_s", 600, "reason", "check the furnace"));
        ToolRun status = call(companion, "task_status", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(walk.task() != null && timer.succeeded(), "goto or set_timer did not go through: "
                    + walk.reply() + " / " + timer.reply());
            helper.assertTrue(status.succeeded() && status.reply().contains(walk.task().publicId())
                            && status.reply().contains("goto") && status.reply().contains("check the furnace"),
                    "task_status does not name the walk and the timer: " + status.reply());
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
                .thenExecuteAfter(5, () -> stop.set(call(companion, "task_stop", args())))
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
        ToolRun timer = call(companion, "set_timer", args("after_s", 600, "reason", "feed the pets"));
        ToolRun stop = call(companion, "task_stop", args("task_id", "t9999"));
        ToolRun status = call(companion, "task_status", args());

        helper.succeedWhen(() -> {
            helper.assertTrue(timer.succeeded(), "set_timer failed: " + timer.reply());
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
        ToolRun timer = call(companion, "set_timer", args("after_s", 1, "reason", "the bread should be baked"));
        EventOutbox outbox = EventOutbox.get(helper.getLevel().getServer());

        helper.succeedWhen(() -> {
            helper.assertTrue(timer.succeeded(), "set_timer failed: " + timer.reply());
            helper.assertTrue(outbox.peek(companion.getUUID()).entries().stream()
                            .anyMatch(e -> e.type().equals("timer") && e.text().contains("the bread should be baked")),
                    "the timer has not fired with its reason: " + outbox.peek(companion.getUUID()).entries());
            outbox.forget(companion.getUUID());
            CompanionFactory.despawn(helper.getLevel().getServer(), companion);
        });
    }
}
