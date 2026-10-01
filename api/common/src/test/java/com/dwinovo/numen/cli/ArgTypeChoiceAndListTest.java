package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 小数、几个固定值之一、资源 id 或 {@code #标签}、一串值这四种参数类型:命令行上怎么读、写错了说什么、快捷工具的 JSON
 * 读出来是否同一个值、schema 与帮助里写成什么。
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
                    .example("gt_more find #minecraft:logs iron_ore --radius 12.5 --kind hostile")
                    .promote("Find things, as a tool.");
            g.server("pick", "Pick one.", ArgTypeChoiceAndListTest::remember, WHAT, MODE, REACH)
                    .example("gt_more pick #minecraft:village --mode far --reach 2");
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
        assertTrue(failed("gt_more find").contains("\nusage: gt_more find <ids...> [--radius <number>] [--kind "
                + "<hostile|passive|player|all>]"), "一个都没写就附上这个动作的用法");
    }

    /** 快捷工具在服务端把 JSON 按同一个参数表读成值,和命令行上读出来的是同一份;数组逐项按同一个读法读。 */
    @Test
    void theShortcutReadsTheSameValuesFromJson() {
        CommandArgs viaLine = ran("gt_more find #minecraft:logs iron_ore --radius 12.5 --kind player");
        assertEquals(viaLine, read("{\"radius\": 12.5, \"kind\": \"player\", \"ids\": [\"#minecraft:logs\", \"iron_ore\"]}"));

        assertTrue(message(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": []}"))
                .startsWith("invalid arguments: argument 'ids': expected a list: id or #tag"));
        assertTrue(message(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": \"stone\"}"))
                .startsWith("invalid arguments: argument 'ids': expected a list: id or #tag"));
        assertTrue(message(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": [\"stone\", \"Gold\"]}"))
                .startsWith("invalid arguments: argument 'ids': expected an id like minecraft:oak_log"));
        assertTrue(message(serve("{\"radius\": 1, \"kind\": \"all\", \"ids\": [\"iron_ore gold_ore\"]}"))
                .startsWith("invalid arguments: argument 'ids': expected a single id or #tag"),
                "数组里的一项只能是一个值,空格不拆");
        assertTrue(message(serve("{\"radius\": 1, \"kind\": \"monsters\", \"ids\": [\"stone\"]}"))
                .startsWith("invalid arguments: argument 'kind': expected one of hostile, passive, player, all"));
    }

    @Test
    void aListTakesSingleValues() {
        assertThrows(IllegalArgumentException.class, () -> ArgType.list(ArgType.text()));
    }

    @Test
    void theSchemaAndTheHelpNameEachType() {
        NumenTool tool = ToolRegistry.get("gt_more_find");
        assertEquals(new Gson().toJson(Schema.object()
                .stringArray("ids", "What to find.", 1)
                .optionalNumber("radius", "How far. Omit to look 16 blocks around.", 1, 64)
                .optionalEnum("kind", "Which kind. Omit to take every kind.", "hostile", "passive", "player", "all")
                .build()), new Gson().toJson(tool.parameterSchema()));
        assertEquals("""
                gt_more find <ids...> [--radius <number>] [--kind <hostile|passive|player|all>]
                  Find things.
                  <ids...> (id or #tag, e.g. minecraft:oak_log or #minecraft:logs; one or more, separated by spaces) — What to find.
                  --radius <number> (number 1-64; optional) — How far. Omit to look 16 blocks around.
                  --kind <hostile|passive|player|all> (one of hostile, passive, player, all; optional) — Which kind. Omit to take every kind.
                  Examples:
                    gt_more find #minecraft:logs iron_ore --radius 12.5 --kind hostile
                  Shortcut tool: gt_more_find.""", onClient("gt_more find --help").message());
        assertEquals("""
                gt_more pick <what> [--mode <near|far>] [--reach <number>]
                  Pick one.
                  <what> (id or #tag, e.g. minecraft:oak_log or #minecraft:logs) — Which one.
                  --mode <near|far> (one of near, far; optional) — How to pick. Omit to pick the nearest.
                  --reach <number> (number 0.5-4.5; optional) — How far to reach. Omit to reach as far as a hand does.
                  Examples:
                    gt_more pick #minecraft:village --mode far --reach 2""", onClient("gt_more pick --help").message());
    }

    private static CommandArgs read(String json) {
        return CommandArgs.fromJson(FIND, JsonParser.parseString(json).getAsJsonObject());
    }

    private static String serve(String json) {
        JsonObject args = JsonParser.parseString(json).getAsJsonObject();
        List<String> replies = new ArrayList<>();
        ToolRegistry.get("gt_more_find").serve("test-call", args, null, replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return replies.get(0);
    }

    private static String message(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("message").getAsString();
    }
}
