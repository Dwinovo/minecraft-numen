package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.script.Modules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帮助是模型读的界面,全从签名写出,写成脚本里的样子(LuaLS 的存根):一组一行一个函数,一个函数给全(参数逐个带说明、选项表与结果的类、例子、
 * 注意、相关)。这里查的是结构:登记了的每个函数都出现、带着自己的签名与登记时写下的说明,帮助里指的函数都是登记了的;措辞不钉。
 */
class ApiDocsTest {

    /** 一组两个函数。 */
    public static final class Pantry {

        private Pantry() {}

        public record Take(@Doc("The item, minecraft:bread.") String item,
                           @Doc("How many.") @Omitted("take one") @Positional Optional<Integer> count,
                           @Doc("Which shelf.") @Omitted("look on every shelf") Optional<String> shelf) {}

        @Doc("What was taken.")
        public record Taken(@Doc("How many came out.") int taken, @Doc("Left on the shelf.") Optional<Integer> left) {}

        @Fn("Take something from the pantry.")
        @Example("gt.gt_pantry.take(\"minecraft:bread\", 2, {shelf = \"top\"})")
        @Note("Instant.")
        @SeeAlso("gt.gt_pantry.list")
        public static Taken take(ServerCall call, Take args) {
            return new Taken(0, Optional.empty());
        }

        @Fn("What the pantry holds.")
        @Example("gt.gt_pantry.list()")
        public static List<String> list(ServerCall call) {
            return List.of();
        }
    }

    @BeforeAll
    static void register() {
        SdkFixture.register("gt_pantry", Pantry.class);
    }

    private static ApiRegistry.Group pantry() {
        return ApiRegistry.group("gt.gt_pantry");
    }

    /** 帮助里写着的 {@code gt.gt_pantry.<名字>}(后面不再接下去)都是登记了的函数。 */
    private static void assertReferencesAreRegistered(String help) {
        var reference = java.util.regex.Pattern.compile("gt\\.gt_pantry\\.(\\w+)(?![\\w.])").matcher(help);
        while (reference.find()) {
            assertNotNull(ApiRegistry.function("gt.gt_pantry." + reference.group(1)),
                    "the help names a function that is not registered: " + reference.group() + "\n" + help);
        }
    }

    @Test
    void aGroupIsOneTypedLinePerFunction() {
        String help = ApiDocs.help("gt.gt_pantry", Modules.factory());
        for (ApiFunction fn : pantry().functions()) {
            String line = help.lines().filter(l -> l.startsWith("---@field " + fn.name() + " ")).findFirst()
                    .orElseThrow(() -> new AssertionError(fn.name() + " is not in the group help:\n" + help));
            assertTrue(line.contains("fun("), line);
            for (ApiFunction.Param p : fn.positionals()) {
                assertTrue(line.contains(p.name() + (p.role() == ApiFunction.Role.REQUIRED ? ": " : "?: ")), line);
            }
            for (String option : fn.options()) {
                assertTrue(line.contains(option + "?: "), line);
            }
            assertTrue(line.contains(fn.summary()), line);
        }
        assertTrue(help.contains("string[]") && help.contains("gt.Taken"), "the result types are written: " + help);
        assertTrue(help.contains("---@field taken integer") && help.contains("---@field left? integer"),
                "the result class is described with its fields: " + help);
        assertReferencesAreRegistered(help);
    }

    @Test
    void aFunctionGivesEverything() {
        ApiFunction take = ApiRegistry.function("gt.gt_pantry.take");
        String help = ApiDocs.help(take.fullName(), Modules.factory());
        assertTrue(help.contains("function gt.gt_pantry.take(item, count, opts) end"), help);
        assertTrue(help.contains(take.summary()), help);
        for (ApiFunction.Param p : take.params()) {
            assertTrue(help.contains(p.name()) && help.contains(p.doc().replaceAll("\\.$", "")), p.name() + ": " + help);
            if (p.omitted() != null) {
                assertTrue(help.contains(p.omitted()), p.name() + " says what happens when it is left out: " + help);
            }
        }
        assertTrue(help.contains("---@param count? integer") && help.contains("---@field shelf? string"), help);
        assertTrue(help.contains("---@return gt.Taken"), help);
        take.examples().forEach(e -> assertTrue(help.contains(e), "the example is shown: " + help));
        take.notes().forEach(n -> assertTrue(help.contains(n), "the note is shown: " + help));
        take.seeAlso().forEach(f -> assertTrue(help.contains(f), "the related function is shown: " + help));
        assertTrue(help.contains("---@field taken integer") && help.contains("---@field left? integer"), help);
        assertReferencesAreRegistered(help);
    }

    @Test
    void theIndexListsTheGroupAndAWrongCallsUsageShowsTheExamples() {
        assertTrue(ApiDocs.index(Modules.factory()).contains("gt.gt_pantry"), ApiDocs.index(Modules.factory()));
        ApiFunction take = ApiRegistry.function("gt.gt_pantry.take");
        String usage = ApiDocs.usage(take);
        assertTrue(usage.startsWith("gt.gt_pantry.take("), usage);
        assertTrue(usage.contains("item") && usage.contains("[count]") && !usage.contains("[item]")
                && usage.contains("shelf"), "the optional position is bracketed and the option is named: " + usage);
        take.examples().forEach(e -> assertTrue(usage.contains(e), "the usage shows the example: " + usage));
    }
}
