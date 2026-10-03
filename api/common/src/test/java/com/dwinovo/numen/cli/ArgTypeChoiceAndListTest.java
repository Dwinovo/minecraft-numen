package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小数、几个固定值之一、资源 id 或 {@code #标签}、一串值这四种参数类型:一行命令上怎么读、写错了说什么、脚本里的调用
 * 读出来是否同一个值、帮助里写成什么。
 */
class ArgTypeChoiceAndListTest {

    static final Param<List<String>> IDS = Param.required("ids", ArgType.list(ArgType.idOrTag()), "What to find.");
    static final Param<Double> RADIUS = Param.optional("radius", ArgType.number(1, 64), "How far.")
            .whenOmitted("look 16 blocks around");
    static final Param<String> KIND = Param.optional("kind", ArgType.oneOf("hostile", "passive", "player", "all"),
            "Which kind.").whenOmitted("take every kind");
    static final List<Param<?>> FIND = List.of(IDS, RADIUS, KIND);

    static final Param<String> WHAT = Param.required("what", ArgType.idOrTag(), "Which one.");
    static final Param<String> MODE = Param.optional("mode", ArgType.oneOf("near", "far"), "How to pick.")
            .whenOmitted("pick the nearest");
    static final Param<Double> REACH = Param.optional("reach", ArgType.number(0.5, 4.5), "How far to reach.")
            .whenOmitted("reach as far as a hand does");

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_more", "A group whose actions take the choice, tag and list types.", g -> {
            g.server("find", "Find things.", ArgTypeChoiceAndListTest::remember, IDS, RADIUS, KIND)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_more.find(\"#minecraft:logs\", \"iron_ore\", {radius = 12.5, kind = \"hostile\"})");
            g.server("pick", "Pick one.", ArgTypeChoiceAndListTest::remember, WHAT, MODE, REACH)
                    .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                    .example("gt_more.pick(\"#minecraft:village\", {mode = \"far\", reach = 2})");
        });
    }

    private static void remember(ServerSource src, CommandArgs args) {
        LAST.set(args);
        src.reply(TaskResult.ok("done").toJson());
    }

    private static CommandArgs ran(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertTrue(out.success(), out.message());
        return LAST.get();
    }

    private static String failed(String line) {
        LAST.set(null);
        CliFixture.Outcome out = onServer(line);
        assertFalse(out.success(), line + " should fail");
        assertNull(LAST.get(), "处理函数不该被调到");
        assertTrue(out.message().startsWith("error: "), out.message());
        return out.message().substring("error: ".length());
    }

    @Test
    void eachTypeReadsItsValueOffTheLine() {
        CommandArgs find = ran("gt_more find #minecraft:logs iron_ore minecraft:deepslate_iron_ore --radius 12.5 "
                + "--kind passive");
        assertEquals(12.5, find.get(RADIUS));
        assertEquals("passive", find.get(KIND));
        assertEquals(List.of("#minecraft:logs", "iron_ore", "minecraft:deepslate_iron_ore"), find.get(IDS),
                "一串值按写下的原文收下:标签带着 #,不写命名空间的也照原样");
        assertEquals(100.0, ran("gt_more find stone --kind all --radius 100").get(RADIUS), "越界的值原样交给处理函数");

        CommandArgs pick = ran("gt_more pick minecraft:fortress");
        assertEquals("minecraft:fortress", pick.get(WHAT));
        assertNull(pick.get(MODE));
        CommandArgs flagged = ran("gt_more pick #minecraft:village --reach 3 --mode near");
        assertEquals("#minecraft:village", flagged.get(WHAT));
        assertEquals("near", flagged.get(MODE));
        assertEquals(3.0, flagged.get(REACH));
    }

    @Test
    void aBadValueSaysWhatWasExpected() {
        assertTrue(failed("gt_more find stone --kind monsters")
                .startsWith("expected one of hostile, passive, player, all at position 26: "));
        assertTrue(failed("gt_more find Iron_Ore --kind all")
                .startsWith("expected an id like minecraft:oak_log at position 13: "));
        assertTrue(failed("gt_more find stone #")
                .startsWith("expected an id like minecraft:oak_log at position 19: "));
        assertTrue(failed("gt_more find stone a:b:c").startsWith("'a:b:c' is not a valid id at position 19: "));
        assertTrue(failed("gt_more find iron_ore,gold_ore")
                .startsWith("Expected whitespace to end one argument, but found trailing data at position 21: "));
        assertTrue(failed("gt_more find stone --radius far").startsWith("Expected double at position 28: "));
        assertTrue(failed("gt_more pick stone --mode middle").startsWith("expected one of near, far at position 26: "));
        assertTrue(failed("gt_more find").contains("\nusage: gt_more.find(ids..., {radius=…, kind=…})"),
                "一个都没写就附上这个动作的用法");
    }

    /** 脚本里的调用在服务端把 JSON 按同一个参数表读成值,和一行命令上读出来的是同一份;数组逐项按同一个读法读。 */
    @Test
    void aScriptCallReadsTheSameValuesFromJson() {
        CommandArgs viaLine = ran("gt_more find #minecraft:logs iron_ore --radius 12.5 --kind player");
        assertEquals(viaLine, read("{\"radius\": 12.5, \"kind\": \"player\", \"ids\": [\"#minecraft:logs\", \"iron_ore\"]}"));
        LAST.set(null);
        assertTrue(CliFixture.lua("gt_more.find({\"#minecraft:logs\", \"iron_ore\"}, {radius = 12.5, kind = \"player\"})")
                .success());
        assertEquals(viaLine, LAST.get(), "从脚本进来,处理函数拿到的是同一份");

        assertTrue(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": []}")
                .startsWith("argument 'ids': expected a list: id or #tag"));
        assertTrue(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": \"stone\"}")
                .startsWith("argument 'ids': expected a list: id or #tag"));
        assertTrue(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": [\"stone\", \"Gold\"]}")
                .startsWith("argument 'ids': expected an id like minecraft:oak_log"));
        assertTrue(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": [\"iron_ore gold_ore\"]}")
                .startsWith("argument 'ids': expected a single id or #tag"),
                "数组里的一项只能是一个值,空格不拆");
        assertTrue(serve("{\"radius\": 1, \"kind\": \"monsters\", \"ids\": [\"stone\"]}")
                .startsWith("argument 'kind': expected one of hostile, passive, player, all"));
    }

    @Test
    void aListTakesSingleValues() {
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.text()));
    }

    @Test
    void theHelpNamesEachType() {
        assertEquals("""
                ---Find things.
                ---@param ids string|string[] What to find.
                ---@param opts? gt_more.find.opts
                function gt_more.find(ids, opts) end

                ---@class gt_more.find.opts
                ---@field radius? number How far. Omit to look 16 blocks around.
                ---@field kind? "hostile"|"passive"|"player"|"all" Which kind. Omit to take every kind.
                -- Examples:
                --   gt_more.find("#minecraft:logs", "iron_ore", {radius = 12.5, kind = "hostile"})""",
                CliFixture.help("gt_more.find"));
        assertEquals("""
                ---Pick one.
                ---@param what string Which one.
                ---@param opts? gt_more.pick.opts
                function gt_more.pick(what, opts) end

                ---@class gt_more.pick.opts
                ---@field mode? "near"|"far" How to pick. Omit to pick the nearest.
                ---@field reach? number How far to reach. Omit to reach as far as a hand does.
                -- Examples:
                --   gt_more.pick("#minecraft:village", {mode = "far", reach = 2})""", CliFixture.help("gt_more.pick"));
    }

    private static CommandArgs read(String json) {
        return CommandArgs.fromJson(FIND, JsonParser.parseString(json).getAsJsonObject());
    }

    private static String serve(String json) {
        return CliFixture.serveJson("gt_more find", json).message();
    }
}
