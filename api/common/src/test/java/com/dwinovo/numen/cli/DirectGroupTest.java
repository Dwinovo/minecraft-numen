package com.dwinovo.numen.cli;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onClient;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 一组直接就是一个动作({@code numen <组> <参数…>}),以及帮助后面接一张只有服务端答得出的目录:整行原样交给
 * 处理函数;客户端解析到帮助就送去服务端,服务端按这次调用算出目录再分页;写错时附的是这个动作的帮助;
 * 这样的组不能再有具名动作。
 */
class DirectGroupTest {

    private static final Param<String> LINE = Param.required("line", ArgType.text(), "What to echo.");
    private static final AtomicReference<String> SEEN = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_direct", "A group that is one action.", g ->
                g.serverDirect("Echo the line back.", (src, args) -> {
                    SEEN.set(args.get(LINE));
                    src.reply(TaskResult.ok("echo " + args.get(LINE)).toJson());
                }, LINE).example("numen gt_direct say hi").catalog("Things the server lists for you:", src -> {
                    List<String> lines = new ArrayList<>();
                    for (int i = 1; i <= 23; i++) {
                        lines.add(String.format("  /thing%02d for %s", i, src.toolCallId()));
                    }
                    return lines;
                }));
    }

    private static final String ACTION_HELP = """
            numen gt_direct <line...>
              Echo the line back.
              <line...> (text, the rest of the line) — What to echo.
              Examples:
                numen gt_direct say hi""";

    @Test
    void theRestOfTheLineGoesToTheOneHandler() {
        CliFixture.Outcome ran = onServer("numen gt_direct /say hello  world");
        assertTrue(ran.success());
        assertEquals("/say hello  world", SEEN.get(), "整行原样,斜杠与空格都不动");
        assertTrue(onClient("numen gt_direct say hi").forwarded, "服务端动作从客户端送过去");
    }

    @Test
    void theCatalogIsWorkedOutOnTheServerAndPaged() {
        CliFixture.Outcome client = onClient("numen gt_direct --help");
        assertTrue(client.forwarded, "目录只有服务端答得出");
        assertTrue(client.replies.isEmpty(), client.replies.toString());

        String first = onServer("numen gt_direct --help").message();
        assertTrue(first.startsWith(ACTION_HELP + "\nThings the server lists for you:\n"
                + "  /thing01 for test-call\n"), first);
        assertTrue(first.endsWith("  /thing20 for test-call\n"
                + "(page 1 of 2, 3 more: numen gt_direct --help --page 2)"), first);

        assertEquals(ACTION_HELP + """

                Things the server lists for you:
                  /thing21 for test-call
                  /thing22 for test-call
                  /thing23 for test-call""", onServer("numen gt_direct --help --page 2").message());

        CliFixture.Outcome beyond = onServer("numen gt_direct --help --page 3");
        assertFalse(beyond.success());
        assertTrue(beyond.message().startsWith("no page 3; numen gt_direct --help has pages 1-2\n" + ACTION_HELP),
                beyond.message());
    }

    @Test
    void aMissingLineComesBackWithTheActionsHelp() {
        CliFixture.Outcome bare = onClient("numen gt_direct");
        assertFalse(bare.success());
        assertTrue(bare.message().endsWith("\n" + ACTION_HELP), bare.message());
        String root = onClient("numen help").message();
        assertTrue(root.contains("\n  gt_direct — A group that is one action.\n"), root);
    }

    @Test
    void aDirectGroupHasNoOtherActions() {
        NumenApi numen = door();
        Action.OnServer ok = (src, args) -> src.reply(TaskResult.ok("ok").toJson());
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_direct_then_named", "x.", g -> {
            g.serverDirect("Direct.", ok, LINE);
            g.server("named", "Named.", ok);
        }));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_named_then_direct", "x.", g -> {
            g.server("named", "Named.", ok);
            g.serverDirect("Direct.", ok, LINE);
        }));
        assertThrows(IllegalArgumentException.class, () -> numen.registerCommands("gt_direct_twice", "x.", g -> {
            g.serverDirect("Direct.", ok, LINE);
            g.serverDirect("Again.", ok, LINE);
        }));
    }
}
