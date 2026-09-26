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

/**
 * {@code task} 的三个动作提升回原来的工具名:模型看到的名字、描述、参数 schema 与改成命令之前逐字相同,
 * 顺序也挨在一起。期望值是改之前那三个手写工具的原样(描述照抄,schema 用它们当时的 {@link Schema} 写法)。
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
    void theShortcutsKeepTheirOldListingWordForWord() {
        assertListing("task_status",
                "Read what you have in flight: the background task (id, what it is, running/queued, elapsed time "
                        + "and remaining budget) and your pending timers (id, seconds left, reason). Instant. "
                        + "Normally you don't need it — a task announces its own end as a task_finished event and "
                        + "a timer fires on its own; use it when the owner asks how things are going, or before "
                        + "deciding what to task_stop.",
                Schema.object().build());
        assertListing("task_stop",
                "Cancel something you dispatched. With no id: aborts the background task (the one "
                        + "<current_task> / task_status shows) so the body frees up; its wind-down arrives as a "
                        + "task_finished event with status=stopped. With an id: cancels that task or that timer "
                        + "(tm...). Fails, listing what is actually pending, when nothing matches.",
                Schema.object()
                        .optionalString("task_id", "What to cancel: a task id (e.g. t42) or a timer id (e.g. tm3). "
                                + "Omit to stop the background task, whatever it is.")
                        .build());
        assertListing("set_timer",
                "Set a one-shot reminder that fires after a delay in world time. Returns immediately and never "
                        + "occupies the body — she keeps doing whatever she is doing. Use it for things the world "
                        + "will not announce on its own: a furnace finishing, crops growing, waiting for daybreak. "
                        + "Do NOT use it to watch work you dispatched yourself — a background task sends its own "
                        + "task_finished event when it ends. The timer only reminds you; it is not proof that the "
                        + "thing you waited for happened, so inspect the world when it fires. Max 1200s, at most 8 "
                        + "pending. World time stops while a single-player world is paused. task_status lists your "
                        + "timers; task_stop cancels one.",
                Schema.object()
                        .integer("after_s", "Delay in world-time seconds (1-1200; out-of-range values are clamped).",
                                1, 1200)
                        .string("reason", "What to look at or decide when it fires. The owner sees this too, so "
                                + "name the thing: \"collect the iron from the furnace\" beats \"check back\".")
                        .build());

        List<String> names = ToolRegistry.all().stream().map(NumenTool::name).toList();
        int at = names.indexOf("task_status");
        assertEquals(List.of("task_status", "task_stop", "set_timer"), names.subList(at, at + 3),
                "三个快捷工具挨着、按原来的顺序进表");
    }

    @Test
    void theGroupsHelpReadsLikeThis() {
        assertEquals("""
                task: Your dispatched work — the background task and your pending timers. Actions:
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
                    It only reminds you. Work you dispatched sends its own task_finished; don't set a timer to watch it.
                    At most 8 pending. World time stops while a single-player world is paused.
                  See also: task status, task stop
                  Shortcut tool: set_timer.""", help("task timer --help"));
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
