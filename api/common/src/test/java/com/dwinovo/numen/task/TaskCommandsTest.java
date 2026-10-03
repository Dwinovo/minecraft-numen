package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.CommandRunner;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code task} 的三个动作是脚本里的三个函数,不加工具;它们的帮助逐字钉住。
 */
class TaskCommandsTest {

    /** 登记这一组不加工具:它的三个动作是脚本里的函数。 */
    private static int toolsBefore;

    @BeforeAll
    static void install() {
        toolsBefore = ToolRegistry.size();
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register(door::set);
        TaskCommands.install(door.get());
    }

    @Test
    void theGroupAddsNoTools() {
        assertEquals(toolsBefore, ToolRegistry.size());
    }

    @Test
    void theGroupsHelpReadsLikeThis() {
        assertEquals("""
                ---The background task and your pending timers.
                ---@class task
                ---@field status fun(): task.status.result What you have in flight: the background task and your pending timers.
                ---@field stop fun(opts?: {task_id?: string}): {task_id?: string, timer_id?: string} Cancel the background task, or a task or timer by its id.
                ---@field timer fun(reason: string, opts?: {after?: integer}): {timer_id: string, remaining_s: integer, reason: string} Set a one-shot reminder that fires after a delay in world time.
                task = {}""",
                help("task --help"));
        assertEquals("""
                ---Set a one-shot reminder that fires after a delay in world time.
                ---@param reason string What to look at or decide when it fires. The owner sees this too, so name the \
                thing: "collect the iron from the furnace" beats "check back".
                ---@param opts? task.timer.opts
                ---@return task.timer.result
                function task.timer(reason, opts) end

                ---@class task.timer.opts
                ---@field after? integer Delay in world-time seconds (1-1200; out-of-range values are clamped). Omit to \
                remind you in 60 seconds.

                ---@class task.timer.result
                ---@field timer_id string
                ---@field remaining_s integer Seconds of world time until it fires.
                ---@field reason string
                -- Examples:
                --   task.timer("collect the iron from the furnace", {after = 300})
                -- Notes:
                --   Returns at once and never occupies your body; your owner is told when and why.
                --   For what the world will not announce on its own: a furnace finishing, crops growing, daybreak. \
                When it fires, look: the reminder is not proof the thing happened.
                --   It only reminds you. Work you dispatched sends its own task_finished; don't set a timer to watch it.
                --   At most 8 pending. World time stops while a single-player world is paused.
                -- See also: task.status, task.stop""", help("task timer --help"));
        assertEquals("""
                ---Cancel the background task, or a task or timer by its id.
                ---@param opts? task.stop.opts
                ---@return task.stop.result
                function task.stop(opts) end

                ---@class task.stop.opts
                ---@field task_id? string What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3). Omit to stop \
                the background task, whatever it is.

                ---@class task.stop.result
                ---@field task_id? string The task it stopped.
                ---@field timer_id? string The timer it cancelled.
                -- Examples:
                --   task.stop()
                --   task.stop({task_id = "tm3"})
                -- Notes:
                --   Instant; does not ask your owner. With no id it stops the background task (the one \
                <current_task> shows) so the body frees up; a stopped task winds down and reports as a task_finished \
                event with status=stopped.
                --   When nothing matches it fails and lists what is pending.
                -- See also: task.status""", help("task stop --help"));
    }

    /** 帮助只有一份;这里经人写的一行命令要(OP 的 {@code /numen drive} 走的就是这一处)。 */
    private static String help(String line) {
        List<String> replies = new ArrayList<>();
        CommandRunner.run(null, "test-call", line, replies::add);
        assertEquals(1, replies.size());
        return JsonParser.parseString(replies.get(0)).getAsJsonObject().get("message").getAsString();
    }
}
