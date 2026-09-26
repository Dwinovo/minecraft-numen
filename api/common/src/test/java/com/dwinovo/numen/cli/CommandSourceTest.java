package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 执行侧与同源:客户端动作当场跑,服务端动作送去服务端;快捷工具的 schema 由参数表生成,调它就是用同一份读好的
 * 参数调同一个处理函数。回执与从 {@code command} 调一字不差、派下的活叫什么,在 GameTest 里对着真服务器验
 * ({@code TaskControlGameTests}、{@code CommandGameTests})。
 */
class CommandSourceTest {

    static final Param<Integer> AFTER = Param.required("after_s", ArgType.integer(1, 1200), "Delay in seconds.");
    static final Param<String> REASON = Param.required("reason", ArgType.text(), "Why.");
    static final Param<String> ID = Param.optional("id", ArgType.word(), "Which one.");
    static final Param<Integer> TRIES = Param.optional("tries", ArgType.integer(1, 5), "How often.");

    /** 服务端处理函数收到的每一份参数。 */
    static final List<CommandArgs> SERVER_CALLS = new ArrayList<>();
    static final List<CommandArgs> CLIENT_CALLS = new ArrayList<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_side", "A group with one action on each side.", g -> {
            g.server("remind", "Set a reminder.", (src, args) -> {
                SERVER_CALLS.add(args);
                src.reply(TaskResult.ok("reminder in " + args.get(AFTER) + "s: " + args.get(REASON),
                        Map.of("tool", src.toolName(), "task", src.taskName())).toJson());
            }, AFTER, REASON).example("numen gt_side remind 60 check the furnace")
                    .promote("gt_side_remind", "Set a reminder, as a tool.");
            g.client("jot", "Jot something down on the owner's client.", (src, args) -> {
                CLIENT_CALLS.add(args);
                src.reply(TaskResult.ok("jotted " + args.get(ID) + " x" + args.get(TRIES)).toJson());
            }, ID, TRIES).example("numen gt_side jot --id a1")
                    .promote("gt_side_jot", "Jot something down, as a tool.");
        });
    }

    @Test
    void aClientActionRunsRightThereAndAServerActionIsShippedWhole() {
        CliFixture.Outcome jot = onClient("numen gt_side jot --id a1");
        assertFalse(jot.forwarded);
        assertEquals("jotted a1 xnull", jot.message());

        int before = SERVER_CALLS.size();
        CliFixture.Outcome remind = onClient("numen gt_side remind 60 check the furnace");
        assertTrue(remind.forwarded, "服务端动作整条送去服务端");
        assertTrue(remind.replies.isEmpty(), "结果等服务端回来");
        assertEquals(before, SERVER_CALLS.size(), "客户端不跑服务端的处理函数");
    }

    /** 服务端的树上客户端动作只有名字与帮助,没有参数、执行不了:写到它那儿是一行没写完的命令,附上它的帮助。 */
    @Test
    void theServerTreeOnlyNamesAClientAction() {
        CliFixture.Outcome jot = onServer("numen gt_side jot --id a1");
        assertFalse(jot.success());
        assertTrue(jot.message().startsWith("Unknown command"), jot.message());
        assertTrue(jot.message().contains("\nnumen gt_side jot [--id <word>] [--tries <integer>]\n"), jot.message());
        assertTrue(onServer("numen gt_side jot --help").success(), "帮助两侧都答得出");
    }

    @Test
    void theShortcutSchemaIsGeneratedFromTheParameters() {
        NumenTool remind = ToolRegistry.get("gt_side_remind");
        assertInstanceOf(PromotedTool.class, remind);
        assertEquals("Set a reminder, as a tool.", remind.description());
        assertEquals(Schema.object()
                .integer("after_s", "Delay in seconds.", 1, 1200)
                .string("reason", "Why.")
                .build(), remind.parameterSchema());
        assertEquals(Schema.object()
                .optionalString("id", "Which one.")
                .optionalInteger("tries", "How often.", 1, 5)
                .build(), ToolRegistry.get("gt_side_jot").parameterSchema());
        assertEquals(Schema.object()
                .string("command", "One command line. Without a leading / it is one of Numen's commands, e.g. "
                        + "\"numen --help\"; with a leading / it is a native command, e.g. \"/help give\".")
                .build(), new CommandTool().parameterSchema());
    }

    /**
     * 同一件事从两个入口进来,处理函数拿到的参数相等:快捷工具在服务端按同一张参数表把 JSON 读成值
     * ({@link CommandArgs#fromJson}),命令从服务端那棵树上读。从 {@code command} 进来,源对象带着 {@code command}
     * 这个工具名,派下的活叫"组 动作"。
     */
    @Test
    void theShortcutAndTheCommandHandTheSameArgumentsToTheSameHandler() {
        SERVER_CALLS.clear();
        JsonObject json = new JsonObject();
        json.addProperty("after_s", 60);
        json.addProperty("reason", "check the furnace");
        CliFixture.Outcome viaCommand = onServer("numen gt_side remind 60 check the furnace");

        assertEquals(1, SERVER_CALLS.size());
        assertEquals(CommandArgs.fromJson(List.of(AFTER, REASON), json), SERVER_CALLS.get(0), "两个入口读出的参数相等");
        assertEquals("reminder in 60s: check the furnace", viaCommand.message());
        assertEquals("command", viaCommand.json().getAsJsonObject("data").get("tool").getAsString());
        assertEquals("gt_side remind", viaCommand.json().getAsJsonObject("data").get("task").getAsString(),
                "从 command 派下的活叫\"组 动作\",不叫 command");
    }

    @Test
    void aShortcutsBadJsonIsRefusedLikeAnyToolsBadArguments() {
        NumenTool remind = ToolRegistry.get("gt_side_remind");
        assertEquals("invalid arguments: missing required argument: after_s",
                message(serve(remind, JsonParser.parseString("{\"reason\":\"x\"}").getAsJsonObject())));
        assertTrue(message(serve(remind, JsonParser.parseString("{\"after_s\":\"soon\",\"reason\":\"x\"}")
                .getAsJsonObject())).startsWith("invalid arguments: argument 'after_s': Expected integer"));
        assertEquals("invalid arguments: unknown argument 'when'; this takes: after_s, reason",
                message(serve(remind, JsonParser.parseString("{\"after_s\":5,\"reason\":\"x\",\"when\":1}")
                        .getAsJsonObject())));
    }

    @Test
    void aShortcutForAClientActionRunsOnTheClientWithTheJsonReadTheSameWay() {
        CLIENT_CALLS.clear();
        String viaCommand = onClient("numen gt_side jot --tries 3 --id a1").replies.get(0);

        List<String> replies = new ArrayList<>();
        UUID companion = UUID.randomUUID();
        ToolRegistry.get("gt_side_jot").invoke(new ToolCall("test-call", "gt_side_jot",
                "{\"id\":\"a1\",\"tries\":3}", () -> companion, replies::add));

        assertEquals(2, CLIENT_CALLS.size(), "快捷工具在客户端当场执行");
        assertEquals(CLIENT_CALLS.get(0), CLIENT_CALLS.get(1));
        assertEquals(List.of(viaCommand), replies, "回执一字不差");
    }

    private static String serve(NumenTool tool, JsonObject args) {
        List<String> replies = new ArrayList<>();
        tool.serve("test-call", args, null, replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return replies.get(0);
    }

    private static String message(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("message").getAsString();
    }

}
