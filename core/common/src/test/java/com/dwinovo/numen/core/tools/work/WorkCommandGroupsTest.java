package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.cli.CommandTool;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code move}、{@code work}、{@code fight}、{@code build} 四组登记得上:每个动作的例子按组的树读得通、相关命令都指得到
 * (登记与第一次读树时查;core 的各组照 NumenCore 同一份登记一起装),提升的两个快捷工具叫原来的名字、参数表是动作的参数表
 * 摊平后的样子,帮助从 {@code command} 入口答得出。动作的执行要身体,在 GameTest 里验。
 */
class WorkCommandGroupsTest {

    @BeforeAll
    static void install() {
        com.dwinovo.numen.core.CoreCommandsFixture.install();
    }

    private static JsonObject run(String line) {
        JsonObject args = new JsonObject();
        args.addProperty("command", line);
        List<String> replies = new ArrayList<>();
        new CommandTool().serve("test-call", args, null, replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return JsonParser.parseString(replies.get(0)).getAsJsonObject();
    }

    @SuppressWarnings("unchecked")
    private static List<String> fields(String tool) {
        NumenTool t = ToolRegistry.get(tool);
        Map<String, Object> props = (Map<String, Object>) t.parameterSchema().get("properties");
        return List.copyOf(props.keySet());
    }

    @SuppressWarnings("unchecked")
    private static List<String> required(String tool) {
        return (List<String>) ToolRegistry.get(tool).parameterSchema().get("required");
    }

    @Test
    void gotoAndMineKeepTheirNamesWithTheRouteFieldsLaidFlat() {
        List<String> route = List.of("alter", "avoid", "penalty_place", "penalty_break", "penalty_jump",
                "penalty_wade", "avoid_break", "avoid_place", "avoid_step", "parkour", "climb_vines", "max_fall",
                "alter_budget");
        List<String> gotoFields = new ArrayList<>(List.of("x", "y", "z", "block", "route"));
        gotoFields.addAll(route);
        assertEquals(gotoFields, fields("goto"));
        assertEquals(List.of(), required("goto"));
        List<String> mineFields = new ArrayList<>(List.of("block_ids", "groups", "count"));
        mineFields.addAll(route);
        assertEquals(mineFields, fields("mine"));
        assertEquals(List.of(), required("mine"));
        for (String gone : List.of("follow", "plan_route", "collect_items", "fish", "attack", "blueprint",
                "blueprint_read", "scaffold_materials", "build")) {
            assertNull(ToolRegistry.get(gone), gone + " 已经是命令,不再是工具");
        }
    }

    @Test
    void eachGroupAnswersItsHelp() {
        for (String group : List.of("move", "work", "fight", "build")) {
            JsonObject help = run(group + " --help");
            assertTrue(help.get("success").getAsBoolean(), help.toString());
            assertTrue(help.get("message").getAsString().startsWith(group + ": "), help.toString());
        }
        String moveHelp = run("move --help").get("message").getAsString();
        assertTrue(moveHelp.contains("\n  move goto [--x <integer>] [--y <integer>] [--z <integer>] [--block <id>] "
                + "[--route <word>] [route flags] — "), "组帮助里路线标志整组写成一格: " + moveHelp);
        assertTrue(!moveHelp.contains("--avoid_break"), moveHelp);
        String gotoHelp = run("move goto --help").get("message").getAsString();
        assertTrue(gotoHelp.startsWith("move goto [--x <integer>] [--y <integer>] [--z <integer>] [--block <id>] "
                + "[--route <word>] [route flags]\n"), gotoHelp);
        assertTrue(gotoHelp.contains("\n  Route flags:\n    --alter <none|natural|any> "), gotoHelp);
        assertTrue(gotoHelp.contains("--avoid_break <block|cell...>"), gotoHelp);
        assertTrue(gotoHelp.endsWith("Shortcut tool: goto."), gotoHelp);
        String mineHelp = run("work mine --help").get("message").getAsString();
        assertTrue(mineHelp.startsWith("work mine [--block_ids <id...>] [--groups <word...>] [--count <integer>]"),
                mineHelp);
        assertTrue(mineHelp.endsWith("Shortcut tool: mine."), mineHelp);
    }
}
