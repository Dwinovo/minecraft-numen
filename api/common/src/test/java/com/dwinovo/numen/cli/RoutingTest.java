package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.lua;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 两个前端、一份登记:她的脚本里客户端动作在主人客户端执行、服务端动作在服务端执行;人写的一行命令在服务端读,行首是 {@code /}
 * 的是原版与模组的指令,其余是 Numen 的动作——认不出的不转给原版。
 */
class RoutingTest {

    @BeforeAll
    static void register() {
        Param<Integer> count = Param.required("count", ArgType.integer(1, 9), "How many.");
        door().registerCommands("gt_route", "One action on each side.", g -> {
            g.server("take", "Take some.", (src, args) -> src.reply(TaskResult.ok("took " + args.get(count))
                    .toJson()), count)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_route.take(2)");
            g.client("jot", "Jot it down.", (src, args) -> src.reply(TaskResult.ok("jotted " + args.get(count))
                    .toJson()), count)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_route.jot(2)");
        });
    }

    @Test
    void theLayerOfALineIsTheLeadingSlashAndNothingElse() {
        assertEquals(new Line(true, "give @s minecraft:diamond 2"), Line.of("  /give @s minecraft:diamond 2 "));
        assertEquals(new Line(true, "help give"), Line.of("/ help give"));
        assertEquals(new Line(false, "gt_route take 2"), Line.of(" gt_route take 2"));
        assertEquals(new Line(false, "give @s minecraft:diamond 2"), Line.of("give @s minecraft:diamond 2"),
                "不带 / 的就是 Numen 的动作,哪怕它像一条原版指令");
    }

    /** 一行命令认不出的在这一层报错,不转给原版指令:像原版指令也一样。 */
    @Test
    void aNumenLineIsNeverHandedToMinecraftsCommands() {
        CliFixture.Outcome give = onServer("give @s minecraft:diamond 2");
        assertFalse(give.success());
        assertTrue(give.message().startsWith("error: Unknown command at position 0: "), give.message());
    }

    @Test
    void aScriptRunsClientActionsOnTheClientAndServerActionsOnTheServer() {
        CliFixture.Outcome both = lua("""
                gt_route.jot(2)
                gt_route.take(3)
                """);
        assertTrue(both.success(), both.message());
        assertEquals("jotted 2", both.call(0).get("message").getAsString(), "客户端动作在客户端执行");
        assertEquals("took 3", both.call(1).get("message").getAsString(), "服务端动作送到服务端执行");
    }

    @Test
    void onTheServerALineRunsServerActionsAndOnlyNamesClientOnes() {
        assertEquals("took 2", onServer("gt_route take 2").message());
        CliFixture.Outcome jot = onServer("gt_route jot 2");
        assertFalse(jot.success(), "服务端的树上客户端动作只有名字与帮助");
    }
}
