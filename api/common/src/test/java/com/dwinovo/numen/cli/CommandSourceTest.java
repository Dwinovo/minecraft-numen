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
 * 参数调同一个处理函数,回执与从命令调一字不差。
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
                        Map.of("tool", src.toolName())).toJson());
            }, AFTER, REASON).promote("gt_side_remind", "Set a reminder, as a tool.");
            g.client("jot", "Jot something down on the owner's client.", (src, args) -> {
                CLIENT_CALLS.add(args);
                src.reply(TaskResult.ok("jotted " + args.get(ID) + " x" + args.get(TRIES)).toJson());
            }, ID, TRIES).promote("gt_side_jot", "Jot something down, as a tool.");
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

    @Test
    void theServerRefusesAClientActionOutright() {
        CliFixture.Outcome jot = onServer("numen gt_side jot");
        assertFalse(jot.success());
        assertEquals("numen gt_side jot runs on the owner's client, not on the server.", jot.message());
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
                .string("command", "The whole command line, starting with numen, e.g. \"numen help\".")
                .build(), new CommandLineTool().parameterSchema());
    }

    @Test
    void theShortcutAndTheCommandCallTheSameHandlerWithTheSameArguments() {
        SERVER_CALLS.clear();
        JsonObject json = new JsonObject();
        json.addProperty("after_s", 60);
        json.addProperty("reason", "check the furnace");
        String viaTool = serve(ToolRegistry.get("gt_side_remind"), json);

        JsonObject line = new JsonObject();
        line.addProperty("command", "numen gt_side remind 60 check the furnace");
        String viaCommand = serve(new CommandLineTool(), line);

        assertEquals(2, SERVER_CALLS.size());
        assertEquals(SERVER_CALLS.get(0), SERVER_CALLS.get(1), "两个入口读出的参数相等");
        assertEquals(message(viaTool), message(viaCommand), "回执同一句话");
        assertEquals("gt_side_remind", data(viaTool).get("tool").getAsString(), "源对象带着进来时的工具名");
        assertEquals("numen", data(viaCommand).get("tool").getAsString());
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

    private static JsonObject data(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("data");
    }
}
