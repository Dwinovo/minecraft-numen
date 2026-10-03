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
 * (登记与第一次用登记处时查;core 的各组照 NumenCore 同一份登记一起装),她唯一的工具是跑脚本的那一个,帮助从 {@code numen.api.help}
 * 答得出。动作的执行要身体,在 GameTest 里验。
 */
class WorkCommandGroupsTest {

    @BeforeAll
    static void install() {
        com.dwinovo.numen.core.CoreCommandsFixture.install();
    }

    private static final UUID HER = UUID.randomUUID();

    /** 一个函数或一组的帮助,经 {@code numen.api.help} 取。 */
    private static String help(String name) {
        CoreScripts.Run run = CoreScripts.run(HER, "return numen.api.help(\"" + name + "\")");
        assertTrue(run.ok(), run.message());
        return run.receipt().getAsJsonObject("data").get("returned").getAsString();
    }

    /** 一组帮助里列的函数名:{@code ---@class 组} 到 {@code 组 = {}} 之间每行 {@code ---@field 名字 fun(...)}。 */
    private static List<String> functions(String help, String group) {
        String body = help.substring(help.indexOf("---@class numen." + group + "\n"),
                help.indexOf("\nnumen." + group + " = {}"));
        return body.lines().filter(l -> l.startsWith("---@field "))
                .map(l -> l.substring("---@field ".length(), l.indexOf(' ', "---@field ".length()))).toList();
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
     * build 组只剩 blueprint/place/diff 与库里的 raise,一页放得下;垫路料是一趟路的描述里的一项({@code numen.route.plan} 的
     * materials),没有一组命令记在她身上。
     */
    @Test
    void buildFitsOnOnePageAndMaterialsAreNoGroup() {
        String build = help("numen.build");
        assertEquals(List.of("blueprint", "place", "diff", "raise"), functions(build, "build"), build);
        assertTrue(build.contains("\n---@field raise fun(building: Cells|Blueprint, opts?: table): integer "),
                "库里的 numen.build.raise 照它的类型注解列在组里: " + build);
        assertTrue(build.contains("\n---@class Placed\n") && build.contains("\n---@class Blueprint\n"),
                "组里返回值用到的类跟在后面: " + build);
        assertTrue(!build.contains("(page 1 of") && !build.contains("scaffold") && !build.contains("throwaway"), build);
        CoreScripts.Run noList = CoreScripts.run(HER, "numen.throwaway.add(\"minecraft:dirt\")");
        assertFalse(noList.ok(), "垫路料不是她身上的一份清单: " + noList.message());
        assertTrue(help("numen.route.plan").contains("\n---@field materials? "), "垫路料写在一趟路的描述里");
        CoreScripts.Run gone = CoreScripts.run(HER, "numen.build.scaffold_add(\"minecraft:dirt\")");
        assertFalse(gone.ok(), "build 组里不再有垫路料的动作: " + gone.message());
        assertTrue(gone.message().contains("there is no API function numen.build.scaffold_add"), gone.message());
    }

    @Test
    void movingItemsInAGuiIsOneStepPerCall() {
        String gui = help("numen.gui");
        assertTrue(gui.contains("\n---@field move fun(from: integer, to: integer, opts?: {count?: integer}) ")
                && gui.contains("\n---@field quick fun(from: integer) "), gui);
        CoreScripts.Run noGui = CoreScripts.run(HER, "numen.gui.move(1)");
        assertTrue(!noGui.ok() && noGui.message().contains("numen.gui.move: bad_argument — ")
                        && noGui.message().contains("argument 'to' is missing"),
                "少写一格目标是参数错、点名少的那个参数: " + noGui.message());
    }

    @Test
    void eachGroupAnswersItsHelp() {
        for (String group : List.of("move", "route", "work", "fight", "build", "inv", "gui", "gear", "creative",
                "time")) {
            String text = help("numen." + group);
            assertTrue(text.startsWith("---") && text.contains("\n---@class numen." + group + "\n")
                    && text.contains("\nnumen." + group + " = {}"), text);
        }
        String routeHelp = help("numen.route");
        assertEquals(List.of("plan"), functions(routeHelp, "route"), "路线不是名词,只有规划: " + routeHelp);
        assertTrue(routeHelp.contains("\n---@field plan fun(opts?: numen.route.plan.opts): Plan"),
                "组帮助里描述整张表按名字引用: " + routeHelp);
        assertTrue(!routeHelp.contains("avoid_break"), routeHelp);
        String planHelp = help("numen.route.plan");
        assertTrue(planHelp.contains("\n---@class numen.route.plan.opts\n") && planHelp.contains("\n---@field costs? ")
                && planHelp.contains("\n---@field stops? ") && planHelp.contains("\n---@field avoid_break? "), planHelp);
        assertTrue(!planHelp.contains("alter"), "没有 alter 这个旋钮了: " + planHelp);
        String digHelp = help("numen.work.dig");
        assertTrue(digHelp.contains("\nfunction numen.work.dig(blocks, opts) end"), digHelp);
        String moveHelp = help("numen.move");
        assertEquals(List.of("go", "follow", "dismount", "to", "flee", "explore"), functions(moveHelp, "move"),
                "库函数与动作都列在组里: " + moveHelp);
        assertTrue(moveHelp.contains("\n---@field to fun(target: Pos|Block|Entity|table, spec?: table): "
                + "{pos: Pos, distance_left: number} Walk to a place in one call: numen.route.plan with the description "
                + "and to = target, then numen.move.go.\n"), "库函数的说明是它注释的第一句: " + moveHelp);
        String workHelp = help("numen.work");
        assertTrue(functions(workHelp, "work").contains("mine") && workHelp.contains("\n---@field mine fun(blocks: "
                + "Block[]): integer Dig out the given blocks"), "挖完一团是内置模块 work 里的 Lua 函数,不是动作: " + workHelp);
    }

    /**
     * 基线里最常写错的几处,照脚本的写法就读得通:实体是位置参数、点一格默认右键、丢东西默认全丢、挖的位置是一串;
     * 读出来是什么由脚本的前端说(只读不执行,一行一个调用)。
     */
    @Test
    void theCallsSheWritesRead() {
        for (String code : List.of("numen.fight.attack(27)", "numen.use.block({x = 120, y = 64, z = -35})",
                "numen.use.block({x = 120, y = 64, z = -35}, {hold = 1.5})", "numen.use.hit({x = 120, y = 64, z = -35})", "numen.use.entity(812, {sneak = true})",
                "numen.inv.drop(\"cobblestone\")", "numen.inv.drop(\"cobblestone\", {count = 32})", "numen.work.dig({x = 120, y = 64, z = -35})",
                "numen.work.dig({name = \"iron_ore\", pos = {x = 120, y = 12, z = -35}}, {x = 121, y = 12, z = -35}, {count = 4})",
                "numen.move.go({id = \"p1\"})", "numen.move.follow(184)", "numen.move.dismount()",
                "numen.scan.blocks(\"iron_ore\")", "numen.scan.entities()",
                "numen.scan.block({x = 1, y = 2, z = 3})", "numen.task.timer(\"check the furnace\", {after = 90})",
                "numen.route.plan({to = {x = 1, y = 2, z = 3}})",
                "numen.route.plan({stops = {{to = {x = 1, z = 2}}}, costs = {dig = true, consent = false}, avoid = {\"water\"}})",
                "numen.build.place({{name = \"stone\", pos = {x = 1, y = 2, z = 3}}})",
                "numen.build.place({name = \"oak_planks\", pos = {x = 0, y = 1, z = 0}})",
                "numen.build.blueprint(\"house\", {x = 100, y = 64, z = -20}, {rotation = 90})",
                "numen.build.diff({blueprint = \"house\", origin = {x = 100, y = 64, z = -20}, rotation = 0})",
                "numen.memory.remember(\"main base -340,68,120\")", "numen.todo.write({\"[>] dig\"})")) {
            var reading = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.calls("t", code,
                    com.dwinovo.numen.cli.NumenCli.scriptCatalog(com.dwinovo.numen.script.Modules.factory()));
            assertNull(reading.error(), code + ": " + reading.error());
            assertEquals(1, reading.calls().size(), code);
            com.dwinovo.numen.cli.NumenCli.invocation(reading.calls().get(0));
        }
        for (String wrong : List.of("numen.fight.attack({entity_ids = {27, 26}})", "numen.fight.attack(27, 26)",
                "numen.use.block(\"right\", {x = 120, y = 64, z = -35})", "numen.inv.drop(\"cobblestone\", 32)",
                "numen.move.go(\"home\")", "numen.route.plan({to = \"ores\"})",
                "numen.route.plan({to = {x = 1, y = 2, z = 3}, alter = \"natural\"})")) {
            var reading = com.dwinovo.numen.agent.script.ScriptEngine.IN_USE.calls("t", wrong,
                    com.dwinovo.numen.cli.NumenCli.scriptCatalog(com.dwinovo.numen.script.Modules.factory()));
            org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> com.dwinovo.numen.cli.NumenCli.invocation(reading.calls().get(0)), "旧写法不再收: " + wrong);
        }
    }
}
