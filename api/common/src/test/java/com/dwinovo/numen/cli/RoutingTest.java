package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由只有一条规则:这一行在主人客户端的小表上解析到客户端动作或帮助,就在客户端当场答;否则原样送服务端。
 * 客户端不认识原版和模组的指令,也不需要认识。
 */
class RoutingTest {

    @BeforeAll
    static void register() {
        Param<Integer> count = Param.required("count", ArgType.integer(1, 9), "How many.");
        door().registerCommands("gt_route", "One action on each side.", g -> {
            g.server("take", "Take some.", (src, args) -> src.reply(TaskResult.ok("took").toJson()), count)
                    .example("numen gt_route take 2");
            g.client("jot", "Jot it down.", (src, args) -> src.reply(TaskResult.ok("jotted").toJson()), count)
                    .example("numen gt_route jot 2");
        });
    }

    private static void answeredOnClient(String line) {
        CliFixture.Outcome out = onClient(line);
        assertFalse(out.forwarded, line + " went to the server");
        assertEquals(1, out.replies.size(), line);
    }

    private static void forwarded(String line) {
        CliFixture.Outcome out = onClient(line);
        assertTrue(out.forwarded, line + " was answered on the client: " + out.replies);
        assertTrue(out.replies.isEmpty(), line);
    }

    @Test
    void helpAndClientActionsAreAnsweredOnTheClient() {
        answeredOnClient("numen help");
        answeredOnClient("numen --help");
        answeredOnClient("numen gt_route --help");
        answeredOnClient("numen gt_route take --help");
        answeredOnClient("numen gt_route jot --help");
        answeredOnClient("numen gt_route jot 2");
        answeredOnClient("numen gt_route jot many");
        answeredOnClient("numen --help --page 9");
    }

    @Test
    void everythingElseGoesToTheServerAsWritten() {
        forwarded("numen gt_route take 2");
        forwarded("numen gt_route take many");
        forwarded("numen gt_route");
        forwarded("numen gt_route tkae 2");
        forwarded("numen gt_nowhere go");
        forwarded("numen");
        forwarded("give @s minecraft:diamond 2");
        forwarded("help give");
        forwarded("ftbteams party join Dwin_Party#1a2b");
    }

    /** {@code command} 工具认的一行,前导 {@code /} 可有可无;送去服务端的是这次调用本身。 */
    @Test
    void theCommandToolTakesALineWithOrWithoutTheSlash() {
        List<String> replies = new ArrayList<>();
        UUID her = UUID.randomUUID();
        new CommandTool().invoke(new ToolCall("call-1", CommandTool.NAME,
                "{\"command\":\"  /numen gt_route jot 2 \"}", () -> her, replies::add));
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).contains("jotted"), replies.get(0));

        JsonObject args = CommandTool.args("numen gt_route take 2");
        assertEquals("numen gt_route take 2", CommandTool.line(args));
        assertEquals("give @s minecraft:diamond", CommandTool.line(CommandTool.args("/give @s minecraft:diamond")));
    }
}
