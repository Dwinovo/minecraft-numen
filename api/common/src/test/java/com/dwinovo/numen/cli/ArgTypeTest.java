package com.dwinovo.numen.cli;

import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.cli.CliFixture.door;
import static com.dwinovo.numen.cli.CliFixture.help;
import static com.dwinovo.numen.cli.CliFixture.serveJson;
import static com.dwinovo.numen.cli.CliFixture.onServer;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 不设范围的整数、开关、资源 id、一个值(带空格加引号)这四种参数类型:命令行上怎么读、写错了说什么、
 * 脚本里的调用读出来是否同一个值、帮助里写成什么。一条命令只有一类位置参数,所以值是位置参数,其余几种是标志。
 */
class ArgTypeTest {

    static final Param<String> MODEL = Param.required("model", ArgType.string(), "Which model.");
    static final Param<Integer> X = Param.optional("x", ArgType.integer(), "Block X.").whenOmitted("use 0");
    static final Param<ResourceLocation> RECIPE = Param.optional("recipe", ArgType.id(), "Which recipe.")
            .whenOmitted("make anything");
    static final Param<Boolean> HAVE_ONLY = Param.optional("have_only", ArgType.bool(), "Only what you can make.")
            .whenOmitted("list everything");
    static final Param<String> SEARCH = Param.optional("search", ArgType.string(), "Narrow the list.")
            .whenOmitted("list all");
    static final Param<Integer> DEPTH = Param.optional("depth", ArgType.integer(), "How far down.")
            .whenOmitted("stay level");
    /** 这个动作的参数表:脚本里的调用在服务端就是按它把 JSON 读成值,再交给处理函数。 */
    static final List<Param<?>> PARAMS = List.of(MODEL, X, RECIPE, HAVE_ONLY, SEARCH, DEPTH);

    static final AtomicReference<CommandArgs> LAST = new AtomicReference<>();

    @BeforeAll
    static void register() {
        door().registerCommands("gt_types", "A group whose action takes one of each new type.", g ->
                g.server("make", "Make something.", (src, args) -> {
                    LAST.set(args);
                    src.reply(TaskResult.ok("made").toJson());
                }, MODEL, X, RECIPE, HAVE_ONLY, SEARCH, DEPTH)
                        .returns(com.dwinovo.numen.agent.script.ScriptType.NOTHING)
                        .example("gt.gt_types.make(\"抽象鸣潮 菲比.ysm\", {x = -12, recipe = \"stone\", have_only = true})"));
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
        CommandArgs plain = ran("gt gt_types make misc/1_Alex --x -12 --recipe kaleidoscope_cookery:flex_pot/braised_beef");
        assertEquals(-12, plain.get(X));
        assertEquals(ResourceLocation.fromNamespaceAndPath("kaleidoscope_cookery", "flex_pot/braised_beef"),
                plain.get(RECIPE));
        assertEquals("misc/1_Alex", plain.get(MODEL), "不加引号时一个值读到空格为止,斜杠与大写照收");
        assertNull(plain.get(HAVE_ONLY));

        CommandArgs flagged = ran("gt gt_types make \"抽象鸣潮 菲比.ysm\" --x 30000000 --recipe stone --have-only "
                + "--search 灵梦 --depth -64");
        assertEquals(30000000, flagged.get(X));
        assertEquals(ResourceLocation.withDefaultNamespace("stone"), flagged.get(RECIPE), "不写命名空间就是 minecraft:");
        assertEquals("抽象鸣潮 菲比.ysm", flagged.get(MODEL), "带空格的值加引号");
        assertEquals(true, flagged.get(HAVE_ONLY));
        assertEquals("灵梦", flagged.get(SEARCH));
        assertEquals(-64, flagged.get(DEPTH));
        assertEquals("say \"hi\"", ran("gt gt_types make \"say \\\"hi\\\"\"").get(MODEL), "引号里反斜杠转义");
        assertEquals(false, ran("gt gt_types make m --no-have-only").get(HAVE_ONLY), "开关写 --no-name 是关");
        assertEquals(true, ran("gt gt_types make m --have_only").get(HAVE_ONLY), "标志名里 _ 与 - 是同一个字符");
    }

    @Test
    void aBadValueSaysWhatWasExpected() {
        assertTrue(failed("gt gt_types make m --recipe Stone").startsWith("expected an id like minecraft:oak_log at position 28: "));
        assertTrue(failed("gt gt_types make m --recipe a:b:c").startsWith("'a:b:c' is not a valid id at position 28: "));
        assertTrue(failed("gt gt_types make \"half open").startsWith("Unclosed quoted string"));
        assertTrue(failed("gt gt_types make m --have-only yes").startsWith("--have-only is a switch and takes no value"),
                "开关不写 true/false");
        assertTrue(failed("gt gt_types make m --x 1.5").startsWith("Invalid integer '1.5'"));
    }

    /**
     * 脚本里的调用在服务端把 JSON 按同一个参数表读成值({@link CommandArgs#fromJson}),交给处理函数——读出来的和一行命令上
     * 读出来的是同一份。从脚本一路走到处理函数、回执一字不差,在 GameTest 里对着真服务器验。
     */
    @Test
    void aScriptCallReadsTheSameValuesFromJson() {
        CommandArgs viaLine = ran("gt gt_types make \"抽象鸣潮 菲比.ysm\" --x -12 "
                + "--recipe kaleidoscope_cookery:flex_pot/braised_beef --no-have-only --search misc/1_Alex");
        JsonObject json = JsonParser.parseString("""
                {"x": -12, "recipe": "kaleidoscope_cookery:flex_pot/braised_beef", "model": "抽象鸣潮 菲比.ysm",
                 "have-only": false, "search": "misc/1_Alex"}""").getAsJsonObject();
        assertEquals(viaLine, CommandArgs.fromJson(PARAMS, json),
                "带空格的名字 JSON 里不用加引号,读出来和命令行上加了引号的是同一个值;键里 - 与 _ 同一个字符");

        assertEquals("say \"hi\" \\ bye", read("{\"x\":1,\"recipe\":\"stone\",\"model\":\"say \\\"hi\\\" \\\\ bye\"}")
                .get(MODEL), "JSON 里的引号与反斜杠原样读回");
        assertTrue(serveJson("gt gt_types make", "{\"x\":1,\"recipe\":\"a b\",\"model\":\"m\"}").message()
                .startsWith("argument 'recipe': expected a single id"));
        assertTrue(serveJson("gt gt_types make", "{\"x\":1,\"recipe\":\"stone\",\"model\":\"m\","
                + "\"have_only\":\"maybe\"}").message().startsWith("argument 'have_only': Invalid bool"));
        assertEquals(viaLine, ranScript("gt.gt_types.make(\"抽象鸣潮 菲比.ysm\", {x = -12, "
                + "recipe = \"kaleidoscope_cookery:flex_pot/braised_beef\", have_only = false, search = \"misc/1_Alex\"})"),
                "从脚本进来,处理函数拿到的是同一份");
    }

    @Test
    void theHelpNamesEachType() {
        assertEquals("""
                ---Make something.
                ---@param model string Which model.
                ---@param opts? gt.gt_types.make.opts
                function gt.gt_types.make(model, opts) end

                ---@class gt.gt_types.make.opts
                ---@field x? integer Block X. Omit to use 0.
                ---@field recipe? string Which recipe. Omit to make anything.
                ---@field have_only? boolean Only what you can make. Omit to list everything.
                ---@field search? string Narrow the list. Omit to list all.
                ---@field depth? integer How far down. Omit to stay level.
                -- Examples:
                --   gt.gt_types.make("抽象鸣潮 菲比.ysm", {x = -12, recipe = "stone", have_only = true})""",
                help("gt.gt_types.make"));
    }

    private static CommandArgs ranScript(String code) {
        LAST.set(null);
        CliFixture.Outcome out = CliFixture.lua(code);
        assertTrue(out.success(), out.message());
        return LAST.get();
    }

    private static CommandArgs read(String json) {
        return CommandArgs.fromJson(PARAMS, JsonParser.parseString(json).getAsJsonObject());
    }

}
