package com.dwinovo.numen.task;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.cli.CommandTool;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@code task} 的三个动作只有 {@code stop} 提升成快捷工具 {@code task_stop}:名字与参数 schema 与改成命令之前的手写工具
 * 逐字相同(schema 用它当时的 {@link Schema} 写法),描述里不再指向已经不是工具的 task_status。{@code status} 与
 * {@code timer} 只作命令,工具表里没有 {@code task_status}、{@code set_timer}。
 */
class TaskCommandsTest {

    private static final Gson GSON = new Gson();

    @BeforeAll
    static void install() {
        AtomicReference<NumenApi> door = new AtomicReference<>();
        NumenPlugins.register(door::set);
        TaskCommands.install(door.get());
    }

    @Test
    void onlyStopIsAShortcut() {
        assertListing("task_stop",
                "Cancel something you dispatched. With no id: aborts the background task (the one "
                        + "<current_task> shows) so the body frees up; its wind-down arrives as a "
                        + "task_finished event with status=stopped. With an id: cancels that task or that timer "
                        + "(tm...). Fails, listing what is actually pending, when nothing matches.",
                Schema.object()
                        .optionalString("task_id", "What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3). "
                                + "Omit to stop the background task, whatever it is.")
                        .build());

        List<String> names = ToolRegistry.all().stream().map(NumenTool::name).toList();
        assertFalse(names.contains("task_status"), "task status 只作命令,不进工具表:" + names);
        assertFalse(names.contains("set_timer"), "task timer 只作命令,不进工具表:" + names);
    }

    @Test
    void theGroupsHelpReadsLikeThis() {
        assertEquals("""
                task: The background task and your pending timers. Actions:
                  task status — What you have in flight: the background task and your pending timers.
                  task stop [--task_id <word>] — Cancel the background task, or a task or timer by its id.
                  task timer <after_s> <reason...> — Set a one-shot reminder that fires after a delay in world time.
                task <action> --help explains one action.""", help("task --help"));
        assertEquals("""
                task timer <after_s> <reason...>
                  Set a one-shot reminder that fires after a delay in world time.
                  <after_s> (integer 1-1200) — Delay in world-time seconds (1-1200; out-of-range values are clamped).
                  <reason...> (text, the rest of the line) — What to look at or decide when it fires. The owner \
                sees this too, so name the thing: "collect the iron from the furnace" beats "check back".
                  Examples:
                    task timer 300 collect the iron from the furnace
                  Notes:
                    Returns at once and never occupies your body; your owner is told when and why.
                    For what the world will not announce on its own: a furnace finishing, crops growing, daybreak. \
                When it fires, look: the reminder is not proof the thing happened.
                    It only reminds you. Work you dispatched sends its own task_finished; don't set a timer to watch it.
                    At most 8 pending. World time stops while a single-player world is paused.
                  See also: task status, task stop""", help("task timer --help"));
        assertEquals("""
                task stop [--task_id <word>]
                  Cancel the background task, or a task or timer by its id.
                  --task_id <word> (word; optional) — What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3). \
                Omit to stop the background task, whatever it is.
                  Examples:
                    task stop
                    task stop --task_id tm3
                  Notes:
                    Instant; does not ask your owner. A stopped task winds down and reports as a task_finished event \
                with status=stopped.
                    When nothing matches it fails and lists what is pending.
                  See also: task status
                  Shortcut tool: task_stop.""", help("task stop --help"));
    }

    private static void assertListing(String name, String description, Map<String, Object> schema) {
        NumenTool tool = ToolRegistry.get(name);
        assertEquals(description, tool.description(), name + " 的描述变了");
        assertEquals(GSON.toJson(schema), GSON.toJson(tool.parameterSchema()), name + " 的 schema 变了");
    }

    /** 从 command 工具在主人客户端这一侧问帮助:当场回,不跑服务端。 */
    private static String help(String line) {
        List<String> replies = new ArrayList<>();
        UUID companion = UUID.randomUUID();
        new CommandTool().invoke(new ToolCall("test-call", CommandTool.NAME,
                GSON.toJson(Map.of("command", line)), () -> companion, replies::add));
        assertEquals(1, replies.size());
        return JsonParser.parseString(replies.get(0)).getAsJsonObject().get("message").getAsString();
    }
}
