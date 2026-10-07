package com.dwinovo.numen.sdk;

import com.dwinovo.numen.script.Modules;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 帮助是模型读的界面,全从签名写出,写成脚本里的样子:一组一行一个函数,一个函数给全(参数逐个带说明、选项表与结果的类、例子、注意、
 * 相关)。样子逐字钉住,措辞一变这里就红。
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

    @Test
    void aGroupIsOneTypedLinePerFunction() {
        assertEquals("""
                ---Test fixture.
                ---@class gt.gt_pantry
                ---@field list fun(): string[] What the pantry holds.
                ---@field take fun(item: string, count?: integer, opts?: {shelf?: string}): gt.Taken Take something \
                from the pantry.
                gt.gt_pantry = {}

                ---What was taken.
                ---@class gt.Taken
                ---@field taken integer How many came out.
                ---@field left? integer Left on the shelf.""", ApiDocs.help("gt.gt_pantry", Modules.factory()));
    }

    @Test
    void aFunctionGivesEverything() {
        assertEquals("""
                ---Take something from the pantry.
                ---@param item string The item, minecraft:bread.
                ---@param count? integer How many. Omit to take one.
                ---@param opts? gt.gt_pantry.take.opts
                ---@return gt.Taken
                function gt.gt_pantry.take(item, count, opts) end

                ---@class gt.gt_pantry.take.opts
                ---@field shelf? string Which shelf. Omit to look on every shelf.
                -- Examples:
                --   gt.gt_pantry.take("minecraft:bread", 2, {shelf = "top"})
                -- Notes:
                --   Instant.
                -- See also: gt.gt_pantry.list

                ---What was taken.
                ---@class gt.Taken
                ---@field taken integer How many came out.
                ---@field left? integer Left on the shelf.""", ApiDocs.help("gt.gt_pantry.take", Modules.factory()));
    }

    @Test
    void theIndexListsTheGroupAndAWrongCallsUsageShowsTheExamples() {
        assertTrue(ApiDocs.index(Modules.factory()).contains("gt.gt_pantry"), ApiDocs.index(Modules.factory()));
        assertEquals("""
                gt.gt_pantry.take(item, [count], {shelf=\u2026})
                  e.g. gt.gt_pantry.take("minecraft:bread", 2, {shelf = "top"})""",
                ApiDocs.usage(ApiRegistry.function("gt.gt_pantry.take")));
    }
}
