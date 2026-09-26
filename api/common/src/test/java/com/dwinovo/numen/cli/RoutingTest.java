package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 路由只有一条规则:行首是 {@code /} 的是第 0 层,原样送服务端;其余是第 1 层,在主人客户端的树上解析——解析到服务端
 * 动作就原样送服务端,客户端动作、帮助与写错的当场答。第 1 层认不出的不转给第 0 层。
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

    private static CliFixture.Outcome answeredOnClient(String line) {
        CliFixture.Outcome out = onClient(line);
        assertFalse(out.forwarded, line + " went to the server");
        assertEquals(1, out.replies.size(), line);
        return out;
    }

    private static void forwarded(String line) {
        CliFixture.Outcome out = onClient(line);
        assertTrue(out.forwarded, line + " was answered on the client: " + out.replies);
        assertTrue(out.replies.isEmpty(), line);
    }

    @Test
    void theLayerIsTheLeadingSlashAndNothingElse() {
        assertEquals(new Line(true, "give @s minecraft:diamond 2"), Line.of("  /give @s minecraft:diamond 2 "));
        assertEquals(new Line(true, "help give"), Line.of("/ help give"));
        assertEquals(new Line(false, "numen gt_route take 2"), Line.of(" numen gt_route take 2"));
        assertEquals(new Line(false, "give @s minecraft:diamond 2"), Line.of("give @s minecraft:diamond 2"),
                "不带 / 的就是第 1 层,哪怕它像一条原版指令");
    }

    @Test
    void helpClientActionsAndMistakesAreAnsweredOnTheClient() {
        answeredOnClient("numen help");
        answeredOnClient("numen --help");
        answeredOnClient("numen gt_route --help");
        answeredOnClient("numen gt_route take --help");
        answeredOnClient("numen gt_route jot --help");
        answeredOnClient("numen gt_route jot 2");
        answeredOnClient("numen gt_route jot many");
        answeredOnClient("numen --help --page 9");
        answeredOnClient("numen gt_route");
        answeredOnClient("numen gt_route tkae 2");
        answeredOnClient("numen gt_nowhere go");
        answeredOnClient("numen");
    }

    /** 第 1 层认不出的一行在第 1 层报错,不转给第 0 层:像原版指令也一样。 */
    @Test
    void aLayerOneLineIsNeverHandedToLayerZero() {
        CliFixture.Outcome give = answeredOnClient("give @s minecraft:diamond 2");
        assertFalse(give.success());
        assertTrue(give.message().startsWith("Unknown command at position 0: "), give.message());
    }

    @Test
    void serverActionsAndEveryNativeLineGoToTheServerAsWritten() {
        forwarded("numen gt_route take 2");
        forwarded("numen gt_route take many");
        forwarded("/give @s minecraft:diamond 2");
        forwarded("/help give");
        forwarded("/numen gt_route jot 2");
        forwarded("/ftbteams party join Dwin_Party#1a2b");
    }

    /** 服务端那一侧同一条规则:第 1 层的一行在 Numen 服务端的树上执行。 */
    @Test
    void onTheServerALayerOneLineRunsOnNumensOwnTree() {
        assertEquals("took", onServer("numen gt_route take 2").message());
        CliFixture.Outcome jot = onServer("numen gt_route jot 2");
        assertFalse(jot.success(), "服务端的树上客户端动作只有名字与帮助");
    }

    /** {@code command} 工具认的一行:不带 {@code /} 在客户端分,带 {@code /} 的整次调用送去服务端。 */
    @Test
    void theCommandToolRoutesByTheSlash() {
        List<String> replies = new ArrayList<>();
        UUID her = UUID.randomUUID();
        new CommandTool().invoke(new ToolCall("call-1", CommandTool.NAME,
                "{\"command\":\"  numen gt_route jot 2 \"}", () -> her, replies::add));
        assertEquals(1, replies.size());
        assertTrue(replies.get(0).contains("jotted"), replies.get(0));

        assertEquals("/give @s minecraft:diamond", CommandTool.line(CommandTool.args("/give @s minecraft:diamond")),
                "调用里的那一行原样留着 /,重放时还落在同一层");
    }
}
