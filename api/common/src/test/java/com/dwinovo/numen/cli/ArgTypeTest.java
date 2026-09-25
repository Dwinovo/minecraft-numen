package com.dwinovo.numen.cli;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.ToolRegistry;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不设范围的整数、布尔、资源 id、一个值(带空格加引号)这四种参数类型:命令行上怎么读、写错了说什么、
 * 快捷工具的 JSON 读出来是否同一个值、schema 与帮助里写成什么。
 */
class ArgTypeTest {

    static final Param<Integer> X = Param.required("x", ArgType.integer(), "Block X.");
    static final Param<ResourceLocation> RECIPE = Param.required("recipe", ArgType.id(), "Which recipe.");
    static final Param<String> MODEL = Param.required("model", ArgType.string(), "Which model.");
    static final Param<Boolean> HAVE_ONLY = Param.optional("have_only", ArgType.bool(), "Only what you can make.");
    static final Param<String> SEARCH = Param.optional("search", ArgType.string(), "Narrow the list.");
    static final Param<Integer> DEPTH = Param.optional("depth", ArgType.integer(), "How far down.");
    /** 这个动作的参数表:快捷工具在服务端就是按它把 JSON 读成值,再交给处理函数。 */
    static final List<Param<?>> PARAMS = List.of(X, RECIPE, MODEL, HAVE_ONLY, SEARCH, DEPTH);

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_types", "A group whose action takes one of each new type.", g ->
                g.server("make", "Make something.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("made").toJson());
                }, X, RECIPE, MODEL, HAVE_ONLY, SEARCH, DEPTH)
                        .example("numen gt_types make -12 stone \"抽象鸣潮 菲比.ysm\" --have_only true")
                        .promote("gt_types_make", "Make something, as a tool."));
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
        return out.message();
    }

    @Test
    void eachTypeReadsItsValueOffTheLine() {
        CommandArgs plain = ran("numen gt_types make -12 kaleidoscope_cookery:flex_pot/braised_beef misc/1_Alex");
        assertEquals(-12, plain.get(X));
        assertEquals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_pot/braised_beef"),
                plain.get(RECIPE));
        assertEquals("misc/1_Alex", plain.get(MODEL), "不加引号时一个值读到空格为止,斜杠与大写照收");
        assertNull(plain.get(HAVE_ONLY));

        CommandArgs flagged = ran("numen gt_types make 30000000 stone \"抽象鸣潮 菲比.ysm\" --have_only true "
                + "--search 灵梦 --depth -64");
        assertEquals(30000000, flagged.get(X));
        assertEquals(ResourceLocation.withDefaultNamespace("stone"), flagged.get(RECIPE), "不写命名空间就是 minecraft:");
        assertEquals("抽象鸣潮 菲比.ysm", flagged.get(MODEL), "带空格的值加引号");
        assertEquals(true, flagged.get(HAVE_ONLY));
        assertEquals("灵梦", flagged.get(SEARCH));
        assertEquals(-64, flagged.get(DEPTH));
        assertEquals("say \"hi\"", ran("numen gt_types make 1 stone \"say \\\"hi\\\"\"").get(MODEL), "引号里反斜杠转义");
    }

    @Test
    void aBadValueSaysWhatWasExpected() {
        assertTrue(failed("numen gt_types make 1 Stone m").startsWith("expected an id like minecraft:oak_log at position 22: "));
        assertTrue(failed("numen gt_types make 1 a:b:c m").startsWith("'a:b:c' is not a valid id at position 22: "));
        assertTrue(failed("numen gt_types make 1 stone \"half open").startsWith("Unclosed quoted string"));
        assertTrue(failed("numen gt_types make 1 stone m --have_only yes").startsWith("Invalid bool, expected true or false"));
        assertTrue(failed("numen gt_types make 1.5 stone m").startsWith("Invalid integer '1.5'"));
    }

    /**
     * 快捷工具在服务端把 JSON 按同一个参数表读成值({@link CommandArgs#fromJson}),交给处理函数——读出来的和命令行上
     * 读出来的是同一份。从工具一路走到处理函数、回执一字不差,在 GameTest 里对着真服务器验。
     */
    @Test
    void theShortcutReadsTheSameValuesFromJson() {
        CommandArgs viaLine = ran("numen gt_types make -12 kaleidoscope_cookery:flex_pot/braised_beef "
                + "\"抽象鸣潮 菲比.ysm\" --have_only false --search misc/1_Alex");
        JsonObject json = JsonParser.parseString("""
                {"x": -12, "recipe": "kaleidoscope_cookery:flex_pot/braised_beef", "model": "抽象鸣潮 菲比.ysm",
                 "have_only": false, "search": "misc/1_Alex"}""").getAsJsonObject();
        assertEquals(viaLine, CommandArgs.fromJson(PARAMS, json),
                "带空格的名字 JSON 里不用加引号,读出来和命令行上加了引号的是同一个值");

        assertEquals("say \"hi\" \\ bye", read("{\"x\":1,\"recipe\":\"stone\",\"model\":\"say \\\"hi\\\" \\\\ bye\"}")
                .get(MODEL), "JSON 里的引号与反斜杠原样读回");
        assertTrue(message(serve(JsonParser.parseString("{\"x\":1,\"recipe\":\"a b\",\"model\":\"m\"}")
                .getAsJsonObject())).startsWith("invalid arguments: argument 'recipe': expected a single id"));
        assertTrue(message(serve(JsonParser.parseString("{\"x\":1,\"recipe\":\"stone\",\"model\":\"m\","
                + "\"have_only\":\"maybe\"}").getAsJsonObject()))
                .startsWith("invalid arguments: argument 'have_only': Invalid bool"));
    }

    @Test
    void theSchemaAndTheHelpNameEachType() {
        NumenTool tool = ToolRegistry.get("gt_types_make");
        assertEquals(new Gson().toJson(Schema.object()
                .integer("x", "Block X.")
                .string("recipe", "Which recipe.")
                .string("model", "Which model.")
                .optionalBool("have_only", "Only what you can make.")
                .optionalString("search", "Narrow the list.")
                .optionalInteger("depth", "How far down.")
                .build()), new Gson().toJson(tool.parameterSchema()));
        assertEquals("""
                numen gt_types make <x> <recipe> <model> [--have_only <boolean>] [--search <string>] [--depth <integer>]
                  Make something.
                  <x> (integer) — Block X.
                  <recipe> (id, e.g. minecraft:oak_log) — Which recipe.
                  <model> (string, quote it if it has spaces) — Which model.
                  --have_only <boolean> (true or false; optional) — Only what you can make.
                  --search <string> (string, quote it if it has spaces; optional) — Narrow the list.
                  --depth <integer> (integer; optional) — How far down.
                  Examples:
                    numen gt_types make -12 stone "抽象鸣潮 菲比.ysm" --have_only true
                  Shortcut tool: gt_types_make.""", onClient("numen gt_types make --help").message());
    }

    private static CommandArgs read(String json) {
        return CommandArgs.fromJson(PARAMS, JsonParser.parseString(json).getAsJsonObject());
    }

    private static String serve(JsonObject args) {
        List<String> replies = new ArrayList<>();
        ToolRegistry.get("gt_types_make").serve("test-call", args, null, replies::add);
        assertEquals(1, replies.size(), "恰好一次回执: " + replies);
        return replies.get(0);
    }

    private static String message(String json) {
        return JsonParser.parseString(json).getAsJsonObject().get("message").getAsString();
    }
}
