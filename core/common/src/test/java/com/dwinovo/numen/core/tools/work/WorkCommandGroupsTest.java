package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.core.CoreScripts;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code move}、{@code work}、{@code fight}、{@code build} 等组登记得上:每个动作的例子经脚本的前端读得通、相关动作都指得到
 * (登记与第一次用登记处时查;core 的各组照 NumenCore 同一份登记一起装),她唯一的工具是跑脚本的那一个,帮助从 {@code api.help}
 * 答得出。动作的执行要身体,在 GameTest 里验。
 */
class WorkCommandGroupsTest {

    @BeforeAll
    static void install() {
        com.dwinovo.numen.core.CoreCommandsFixture.install();
    }

    private static final UUID HER = UUID.randomUUID();

    /** 一个函数或一组的帮助,经 {@code api.help} 取。 */
    private static String help(String name) {
        CoreScripts.Run run = CoreScripts.run(HER, "return api.help(\"" + name + "\")");
        assertTrue(run.ok(), run.message());
        return run.receipt().getAsJsonObject("data").get("returned").getAsString();
    }

    /** 一次调用的回执。 */
    private static JsonObject call(String code) {
        return CoreScripts.run(HER, code).lastReply();
    }

    @Test
    void theOnlyToolIsTheOneThatRunsAProgram() {
        assertEquals(List.of(com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.toolName()),
                ToolRegistry.all().stream().map(t -> t.name()).toList());
        for (String gone : List.of("move_goto", "work_dig", "status_self", "scan_blocks", "skill_load", "todowrite",
                "task_stop", "command", "work_mine", "follow", "plan_route", "collect_items", "fish", "attack",
                "blueprint", "blueprint_read", "scaffold_materials", "build", "transfer")) {
            assertNull(ToolRegistry.get(gone), gone + " 是脚本里的函数,不是工具");
        }
    }

    /**
     * build 组只剩原语、设计与 at/left/built,一页放得下;她赶路时愿意消耗的方块是自己的一组 throwaway,四个动作只改清单,没有
     * "看清单"的动作(现状在身体状态里)。
     */
    @Test
    void buildFitsOnOnePageAndThrowawayIsItsOwnGroup() {
        String build = help("build");
        List<String> actions = build.lines().filter(l -> l.startsWith("  build."))
                .map(l -> l.substring("  build.".length(), l.indexOf('('))).toList();
        assertEquals(List.of("set", "place", "line", "layer", "cylinder", "sphere", "copy", "new", "show", "drop",
                "designs", "delete", "at", "left", "built", "raise"), actions, build);
        assertTrue(build.contains("\n  build.raise(name, opts) — ") && build.endsWith("(library build)"),
                "库里的 build.raise 列在组里、标明出自库: " + build);
        assertTrue(!build.contains("(page 1 of") && !build.contains("scaffold") && !build.contains("throwaway"), build);
        String throwaway = help("throwaway");
        assertEquals(List.of("add", "remove", "set", "clear"), throwaway.lines()
                .filter(l -> l.startsWith("  throwaway.")).map(l -> l.substring("  throwaway.".length(), l.indexOf('(')))
                .toList(), throwaway);
        assertTrue(throwaway.startsWith("throwaway — Your own setting: "), throwaway);
        CoreScripts.Run gone = CoreScripts.run(HER, "build.scaffold_add(\"minecraft:dirt\")");
        assertFalse(gone.ok(), "build 组里不再有垫路料的动作: " + gone.message());
        assertTrue(gone.message().contains("there is no API function build.scaffold_add"), gone.message());
    }

    @Test
    void movingItemsInAGuiIsOneStepPerCall() {
        String use = help("use");
        assertTrue(use.contains("\n  use.transfer(from, to, {count=…}) — ") && use.contains("\n  use.shift(from) — "),
                use);
        CoreScripts.Run noGui = CoreScripts.run(HER, "use.transfer(1)");
        assertTrue(!noGui.ok() && noGui.message().contains("use.transfer(from, to"),
                "少写一格目标就附上这个动作的用法: " + noGui.message());
    }

    @Test
    void eachGroupAnswersItsHelp() {
        for (String group : List.of("move", "route", "work", "fight", "build", "throwaway")) {
            assertTrue(help(group).startsWith(group + " — "), help(group));
        }
        String routeHelp = help("route");
        assertTrue(routeHelp.contains("\n  route.new([name], {to=…, arrive=…, near=…, …route flags}) — "),
                "组帮助里路线标志整组写成一格: " + routeHelp);
        assertTrue(!routeHelp.contains("avoid_break"), routeHelp);
        String newHelp = help("route.new");
        assertTrue(newHelp.contains("\n  Route flags (options):\n    alter= (one of none, natural, any;"), newHelp);
        assertTrue(newHelp.contains("avoid_break="), newHelp);
        String digHelp = help("work.dig");
        assertTrue(digHelp.startsWith("work.dig(place..., {count=…})\n"), digHelp);
        String moveHelp = help("move");
        assertTrue(moveHelp.contains("move.goto_") && moveHelp.contains("move.go("), "库函数与动作都列在组里: " + moveHelp);
        CoreScripts.Run mine = CoreScripts.run(HER, "work.mine(\"ores\")");
        assertTrue(!mine.ok() && mine.message().contains("there is no API function work.mine"),
                "work mine 删了,没有别名: " + mine.message());
    }

    /**
     * 基线里最常写错的几处,照脚本的写法就读得通:实体是位置参数、点一格默认右键、丢东西默认全丢、挖的位置是一串;
     * 读出来是什么由脚本的前端说(只读不执行,一行一个调用)。
     */
    @Test
    void theCallsSheWritesRead() {
        for (String code : List.of("fight.attack(27)", "use.block({120, 64, -35})",
                "use.block({120, 64, -35}, {left = true, hold = 1.5})", "use.entity(812, {sneak = true})",
                "inv.drop(\"cobblestone\")", "inv.drop(\"cobblestone\", {count = 32})", "work.dig({120, 64, -35})",
                "work.dig(\"ores/g3\", {120, 12, -35}, {count = 4})", "move.go(\"home\")", "move.follow(184)",
                "area.parts(\"ores\")", "area.has(\"ores/g3\")", "area.drop(\"ores/g2\")",
                "area.grow(\"buffer\", \"house\")", "scan.blocks(\"iron_ore\")", "scan.entities()",
                "scan.block({1, 2, 3})", "task.timer(\"check the furnace\", {after = 90})", "route.new(\"back\")",
                "route.drop(\"home\")", "build.set({1, 2, 3}, {block = \"stone\"})",
                "build.layer({\"###\"}, {at = {0, 1, 0}, block = \"oak_planks\", into = \"house\", step = 2})",
                "build.drop(\"house/4\")", "build.at(\"house\", {at = {100, 64, -20}})", "build.left(\"house\")",
                "memory.remember(\"main base -340,68,120\")", "todo.write({\"[>] dig\"})")) {
            var reading = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.calls("t", code,
                    com.dwinovo.numen.cli.NumenCli.scriptCatalog());
            assertNull(reading.error(), code + ": " + reading.error());
            assertEquals(1, reading.calls().size(), code);
            com.dwinovo.numen.cli.NumenCli.invocation(reading.calls().get(0));
        }
        for (String wrong : List.of("fight.attack({entity_ids = {27, 26}})", "fight.attack(27, 26)",
                "use.block(\"right\", {120, 64, -35})", "inv.drop(\"cobblestone\", 32)")) {
            var reading = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.calls("t", wrong,
                    com.dwinovo.numen.cli.NumenCli.scriptCatalog());
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> com.dwinovo.numen.cli.NumenCli.invocation(reading.calls().get(0)), "旧写法不再收: " + wrong);
        }
    }
}
