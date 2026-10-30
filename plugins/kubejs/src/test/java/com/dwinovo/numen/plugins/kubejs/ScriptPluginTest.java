package com.dwinovo.numen.plugins.kubejs;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.plugin.PluginRoster;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.ApiTester;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本插件这一层自己的逻辑(不起 JavaScript 引擎,脚本里写的那几个回调在这里用 Java lambda 代替):函数的说明必填、类型名读成值转换、
 * JavaScript 交回的 double 落成函数声明的类型、声明的插件经门登记之后她调得到、撤回之后函数不在、重登回来。
 */
class ScriptPluginTest {

    @BeforeAll
    static void install() {
        NumenApiFixture.install();
    }

    private static ScriptPlugin plugin(String namespace) {
        ScriptPlugin plugin = new ScriptPlugin(namespace);
        plugin.group("calc", "Test fixture: small sums.", group -> {
            group.fn("double", fn -> fn
                    .summary("Double a whole number.")
                    .param("n", "integer", "The number.")
                    .option("extra", "integer", "Added after doubling.", "add nothing")
                    .returns("integer")
                    .example(namespace + ".calc.double(4)")
                    // JavaScript 的数都是 double:交回 8.0,函数声明的是 integer
                    .run(call -> ((Number) call.arg("n")).doubleValue() * 2
                            + (call.option("extra") == null ? 0 : ((Number) call.option("extra")).doubleValue())));
            group.fn("join", fn -> fn
                    .summary("Join words with spaces.")
                    .rest("words", "string", "The words.")
                    .returns("string")
                    .example(namespace + ".calc.join(\"a\", \"b\")")
                    .run(call -> String.join(" ", ((List<?>) call.arg("words")).stream().map(String::valueOf).toList())));
        });
        return plugin;
    }

    private static ApiTester.Run run(String code) {
        return ApiTester.run(null, UUID.randomUUID(), code);
    }

    @Test
    void aFunctionWithoutItsDocsIsRejectedSayingWhatIsMissing() {
        ScriptGroup group = new ScriptGroup("gt_kjs_docs", "calc");

        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> group.fn("bare", fn -> fn
                .param("n", "integer", "")
                .run(call -> null)));

        assertTrue(missing.getMessage().contains("gt_kjs_docs.calc.bare"), missing.getMessage());
        assertTrue(missing.getMessage().contains("a summary"), missing.getMessage());
        assertTrue(missing.getMessage().contains("a doc for the argument 'n'"), missing.getMessage());
        assertThrows(IllegalArgumentException.class, () -> group.fn("mute", fn -> fn.summary("No body.")));
        assertThrows(IllegalArgumentException.class, () -> group.fn("odd", fn -> fn
                .summary("Odd type.").param("n", "float", "A float.").run(call -> null)),
                "a type name a script cannot use is named with the valid ones");
    }

    @Test
    void aDeclaredPluginIsCalledWithTheArgumentsReadAndTheValueCoerced() {
        NumenPlugins.register("gt_kjs_call", plugin("gt_kjs_call")::setup);

        assertEquals(8, run("return gt_kjs_call.calc.double(4)").returned().getAsInt());
        assertEquals(9, run("return gt_kjs_call.calc.double(4, {extra = 1})").returned().getAsInt());
        assertEquals("a b c", run("return gt_kjs_call.calc.join(\"a\", \"b\", \"c\")").returned().getAsString());
        ApiTester.Run bad = run("gt_kjs_call.calc.double(\"four\")");
        assertTrue(bad.message().contains("bad_argument"), bad.message());
    }

    /** 声明的插件和别的插件一样:撤回之后函数不在,重登回来;脚本不能往别的来源的插件里加登记。 */
    @Test
    void aScriptPluginIsWithdrawnAndRegisteredLikeAnyOther() {
        NumenPlugins.admit(PluginRoster.Source.SCRIPT, "gt_kjs_gone", "");
        NumenPlugins.register("gt_kjs_gone", plugin("gt_kjs_gone")::setup);
        assertNotNull(ApiRegistry.function("gt_kjs_gone.calc.double"));
        assertEquals(PluginRoster.Source.SCRIPT, NumenPlugins.roster().plugins().stream()
                .filter(p -> p.id().equals("gt_kjs_gone")).findFirst().orElseThrow().source());

        NumenPlugins.roster().withdraw("gt_kjs_gone");
        assertNull(ApiRegistry.function("gt_kjs_gone.calc.double"));
        assertTrue(NumenPlugins.roster().plugins().stream().noneMatch(p -> p.id().equals("gt_kjs_gone")));

        NumenPlugins.admit(PluginRoster.Source.SCRIPT, "gt_kjs_gone", "");
        NumenPlugins.register("gt_kjs_gone", plugin("gt_kjs_gone")::setup);
        assertNotNull(ApiRegistry.function("gt_kjs_gone.calc.double"));

        NumenPlugins.register("gt_kjs_java", numen -> { });
        assertThrows(IllegalArgumentException.class,
                () -> NumenPlugins.admit(PluginRoster.Source.SCRIPT, "gt_kjs_java", ""));
    }

    /** 知识来源的说明必填(它缺席时模型据此知道缺了什么)、执行体必填、种类要认得。 */
    @Test
    void aKnowledgeSourceNeedsItsProvidesAndBodyAndAKnownKind() {
        ScriptPlugin plugin = new ScriptPlugin("gt_kjs_know_bad");

        IllegalArgumentException noProvides = assertThrows(IllegalArgumentException.class,
                () -> plugin.knowledgeSource("info", source -> source.run(item -> null)));
        assertTrue(noProvides.getMessage().contains("provides"), noProvides.getMessage());
        assertThrows(IllegalArgumentException.class, () -> plugin.knowledgeSource("info", source -> source.provides("x")));
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> plugin.knowledgeSource("lore", source -> source.provides("x").run(item -> null)));
        assertTrue(unknown.getMessage().contains("recipes, uses and info"), unknown.getMessage());
    }

    /** 声明的来源经门登记进来源表(同 Java 插件),说清它读不到什么;撤回之后不在。 */
    @Test
    void aDeclaredKnowledgeSourceIsListedWithWhatItCannotSayAndWithdrawnWithItsPlugin() {
        ScriptPlugin plugin = new ScriptPlugin("gt_kjs_know");
        plugin.knowledgeSource("recipes", source -> source.provides("Pack machine recipes.").notProvided("chance", "energy")
                .run(item -> List.of()));
        NumenPlugins.admit(PluginRoster.Source.SCRIPT, "gt_kjs_know", "");
        NumenPlugins.register("gt_kjs_know", plugin::setup);

        var listed = com.dwinovo.numen.api.knowledge.KnowledgeSources.of(com.dwinovo.numen.api.knowledge.KnowledgeKind.RECIPES)
                .stream().filter(s -> s.id().equals("gt_kjs_know")).toList();
        assertEquals(1, listed.size());
        assertEquals(List.of("chance", "energy"), listed.get(0).notProvided());
        assertEquals("gt_kjs_know.recipes_source.read", listed.get(0).call());

        NumenPlugins.roster().withdraw("gt_kjs_know");
        assertTrue(com.dwinovo.numen.api.knowledge.KnowledgeSources.of(com.dwinovo.numen.api.knowledge.KnowledgeKind.RECIPES)
                .stream().noneMatch(s -> s.id().equals("gt_kjs_know")));
    }
}
