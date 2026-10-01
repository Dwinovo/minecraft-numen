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
    void moveGotoLaysTheRouteFieldsFlatAndWorkDigTakesNone() {
        List<String> route = List.of("alter", "avoid", "allow", "penalty_place", "penalty_break", "penalty_jump",
                "penalty_wade", "avoid_break", "avoid_place", "avoid_step", "parkour", "max_fall", "alter_budget");
        List<String> gotoFields = new ArrayList<>(List.of("place", "arrive", "near"));
        gotoFields.addAll(route);
        assertEquals(gotoFields, fields("move_goto"));
        assertEquals(List.of("place"), required("move_goto"), "去处是 move goto 唯一的位置参数");
        assertEquals(List.of("place", "count"), fields("work_dig"));
        assertEquals(List.of("place"), required("work_dig"));
        for (String gone : List.of("work_mine", "follow", "plan_route", "collect_items", "fish", "attack", "blueprint",
                "blueprint_read", "scaffold_materials", "build", "transfer")) {
            assertNull(ToolRegistry.get(gone), gone + " 已经是命令,不再是工具");
        }
    }

    /**
     * build 组只剩原语、设计与 at/built 十六个动作,一页放得下;她赶路时愿意消耗的方块是自己的一组 throwaway,四个动作只改
     * 清单,没有"看清单"的动作(现状在身体状态里)。
     */
    @Test
    void buildFitsOnOnePageAndThrowawayIsItsOwnGroup() {
        String build = run("build --help").get("message").getAsString();
        List<String> actions = build.lines().filter(l -> l.startsWith("  build ")).map(l -> l.split(" ")[3]).toList();
        assertEquals(List.of("set", "place", "line", "layer", "cylinder", "sphere", "copy", "new", "show", "drop",
                "designs", "delete", "at", "built"), actions, build);
        assertTrue(!build.contains("(page 1 of") && !build.contains("scaffold") && !build.contains("throwaway"), build);
        String throwaway = run("throwaway --help").get("message").getAsString();
        assertEquals(List.of("add", "remove", "set", "clear"), throwaway.lines()
                .filter(l -> l.startsWith("  throwaway ")).map(l -> l.split(" ")[3]).toList(), throwaway);
        assertTrue(throwaway.startsWith("throwaway: Your own setting: "), throwaway);
        JsonObject gone = run("build scaffold_add minecraft:dirt");
        assertTrue(!gone.get("success").getAsBoolean(), "build 组里不再有垫路料的动作: " + gone);
    }

    @Test
    void movingItemsInAGuiIsOneStepPerLine() {
        String use = run("use --help").get("message").getAsString();
        assertTrue(use.contains("\n  use transfer <from> <to> [--count <integer>] — ")
                && use.contains("\n  use shift <from> — "), use);
        JsonObject noGui = run("use transfer 1");
        assertTrue(!noGui.get("success").getAsBoolean()
                        && noGui.get("message").getAsString().contains("use transfer <from> <to>"),
                "少写一格目标就附上这个动作的用法: " + noGui);
    }

    @Test
    void eachGroupAnswersItsHelp() {
        for (String group : List.of("move", "route", "work", "fight", "build", "throwaway")) {
            JsonObject help = run(group + " --help");
            assertTrue(help.get("success").getAsBoolean(), help.toString());
            assertTrue(help.get("message").getAsString().startsWith(group + ": "), help.toString());
        }
        String moveHelp = run("move --help").get("message").getAsString();
        assertTrue(moveHelp.contains("\n  move goto <place> [--arrive <at|use|near|dig>] [--near <integer>] [route flags] — "),
                "组帮助里路线标志整组写成一格: " + moveHelp);
        assertTrue(!moveHelp.contains("--avoid-break"), moveHelp);
        String gotoHelp = run("move goto --help").get("message").getAsString();
        assertTrue(gotoHelp.startsWith("move goto <place> [--arrive <at|use|near|dig>] [--near <integer>] "
                + "[route flags]\n"), gotoHelp);
        assertTrue(gotoHelp.contains("\n  Route flags:\n    --alter <none|natural|any> "), gotoHelp);
        assertTrue(gotoHelp.contains("--avoid-break <block|cell|area...>"), gotoHelp);
        assertTrue(gotoHelp.endsWith("Shortcut tool: move_goto."), gotoHelp);
        String digHelp = run("work dig --help").get("message").getAsString();
        assertTrue(digHelp.startsWith("work dig <place...> [--count <integer>]\n"), digHelp);
        assertTrue(digHelp.endsWith("Shortcut tool: work_dig."), digHelp);
        JsonObject mine = run("work mine ores");
        assertTrue(!mine.get("success").getAsBoolean(), "work mine 删了,没有别名: " + mine);
    }

    /**
     * 基线里最常写错的几行,照 bash 的习惯写就读得通:实体是位置参数、点一格默认右键、丢东西默认全丢、挖的位置是一整串坐标;
     * 读出来是什么由命令树说(只读不执行,{@link com.dwinovo.numen.cli.NumenCli#read})。
     */
    @Test
    void theLinesSheWritesTheBashWayRead() {
        for (String line : List.of("fight attack 27 26", "fight attack", "use block 120 64 -35",
                "use block 120,64,-35 --left --hold 1.5", "use entity 812 --sneak", "inv drop cobblestone",
                "inv drop cobblestone --count 32", "work dig 120 64 -35", "work dig ores/g3 120 12 -35 --count 4",
                "move goto ores --arrive dig", "move goto 120 -35", "move follow 184", "area parts ores",
                "area has ores/g3", "area drop ores/g2", "area grow buffer house", "scan blocks iron_ore",
                "scan entities", "scan block 1 2 3", "task timer \"check the furnace\" --after 90",
                "route new back", "route drop home", "build set 1 2 3 --block stone",
                "build layer ### --at 0 1 0 --block oak_planks --into house --step 2", "build drop house/4",
                "build at house --at 100 64 -20", "memory remember \"main base -340,68,120\"")) {
            assertTrue(com.dwinovo.numen.cli.NumenCli.read(line).runnable(), line);
        }
        com.dwinovo.numen.cli.NumenCli.Reading click = com.dwinovo.numen.cli.NumenCli.read("use block 120 64 -35");
        assertEquals("use block", click.path());
        for (String wrong : List.of("fight attack --entity_ids 27 26", "use block right 120 64 -35",
                "inv drop cobblestone 32", "use block 120 64 -35 --sneak true")) {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> com.dwinovo.numen.cli.NumenCli.read(wrong), "旧写法不再收: " + wrong);
        }
    }
}
