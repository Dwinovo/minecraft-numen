package com.dwinovo.numen.api.sdk;

import com.dwinovo.numen.api.agent.scope.Handle;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ApiSearch} 的查询:{@code select:} 按名字取插件、组、函数,关键字按匹配程度取最前的几条,{@code +词} 要求名字里有,查不到说有哪些插件。
 * 每一条的文字是 {@link ApiDocs} 写的,这里查的是取哪些、按什么顺序、说什么。
 */
class ApiSearchTest {

    private static final String GUIDE = "# Smithing\nThe gts plugin is for working metal: put things in the kiln, fuel it, "
            + "take them out.";

    /** 一个窑:熔炼与添燃料。 */
    public static final class Kiln {

        private Kiln() {}

        public record Item(@Doc("The item to work, minecraft:iron_ore.") String item) {}

        @Fn("Smelt an item in a furnace.")
        @Example("gts.kiln.smelt(\"minecraft:iron_ore\")")
        public static String smelt(ServerCall call, Item args) {
            return "";
        }

        @Fn("Put fuel into the burner.")
        public static String refuel(ServerCall call, Item args) {
            return "";
        }
    }

    /** 一个烤炉。 */
    public static final class Oven {

        private Oven() {}

        @Fn("Bake bread until it is done.")
        public static String bake(ServerCall call) {
            return "";
        }
    }

    private static Handle guide;

    @BeforeAll
    static void register() {
        SdkFixture.ready();
        ApiRegistry.register("gts", "kiln", "A kiln for metal.", Kiln.class);
        ApiRegistry.register("gts", "oven", "An oven for food.", Oven.class);
        guide = ApiRegistry.registerGuide("gts", GUIDE);
    }

    @AfterAll
    static void withdraw() {
        guide.close();
    }

    private static String search(String query) {
        return ApiSearch.search(query, 0, null);
    }

    @Test
    void aPluginReturnsItsGuideAndItsGroups() {
        String text = search("select:gts");
        assertTrue(text.startsWith(GUIDE), text);
        assertTrue(text.contains("gts.kiln — A kiln for metal. refuel, smelt"), text);
        assertTrue(text.contains("gts.oven — An oven for food. bake"), text);
    }

    @Test
    void aGroupReturnsEveryFunctionsSignatureAndTheClassesTheyUse() {
        String text = search("select:gts.kiln");
        assertTrue(text.contains("---@class gts.kiln"), text);
        assertTrue(text.contains("smelt") && text.contains("refuel"), text);
        assertFalse(text.contains("gts.oven"), "only that group: " + text);
    }

    @Test
    void aFunctionReturnsItsFullDocumentation() {
        String text = search("select:gts.kiln.smelt");
        assertTrue(text.contains("function gts.kiln.smelt(item) end"), text);
        assertTrue(text.contains("Smelt an item in a furnace."), text);
        assertTrue(text.contains("gts.kiln.smelt(\"minecraft:iron_ore\")"), "the example is shown: " + text);
        assertFalse(text.contains("refuel"), text);
    }

    @Test
    void severalNamesAreReturnedInTheOrderGivenAndAnUnknownOneIsSaid() {
        String text = search("select:gts.oven.bake, gts.kiln.smelt, gts.nothing");
        assertTrue(text.indexOf("function gts.oven.bake(") < text.indexOf("function gts.kiln.smelt("), text);
        assertTrue(text.endsWith("No plugin, group or function is named gts.nothing."), text);
    }

    @Test
    void theSelectPrefixIsNotCaseSensitiveAndNamesTolerateCase() {
        assertTrue(search("SELECT:GTS.Oven.Bake").contains("function gts.oven.bake("));
    }

    @Test
    void nothingFoundSaysSoAndListsThePlugins() {
        String text = search("select:nowhere");
        assertTrue(text.startsWith("No match for \"select:nowhere\"."), text);
        assertTrue(text.contains("gts") && text.contains("numen"), "the plugins right now are listed: " + text);
        String keywords = search("zzzzzz");
        assertTrue(keywords.startsWith("No match for \"zzzzzz\"."), keywords);
    }

    @Test
    void keywordsRankANameMatchAboveASummaryMatch() {
        String text = search("smelt");
        int function = text.indexOf("function gts.kiln.smelt(");
        assertTrue(function >= 0, text);
        assertTrue(function < text.indexOf("gts.kiln — ") || !text.contains("gts.kiln — "),
                "the function named smelt comes before the one that only mentions it: " + text);
    }

    @Test
    void keywordsAlsoReadTheSummaryOfEachFunction() {
        String text = search("furnace");
        assertTrue(text.contains("function gts.kiln.smelt("), "summary says furnace: " + text);
        assertFalse(text.contains("function gts.oven.bake("), text);
    }

    @Test
    void aPlusTermMustBeInTheNameAndTheRestRank() {
        String text = search("+oven bread");
        assertTrue(text.contains("function gts.oven.bake("), text);
        assertFalse(text.contains("gts.kiln"), text);
        String onlyRequired = ApiSearch.search("+kiln", 25, null);
        assertTrue(onlyRequired.contains("function gts.kiln.smelt(") && onlyRequired.contains("function gts.kiln.refuel("),
                onlyRequired);
        assertFalse(onlyRequired.contains("gts.oven"), onlyRequired);
    }

    @Test
    void aPlusTermNothingContainsMatchesNothing() {
        assertTrue(search("+nosuchplugin smelt").startsWith("No match"));
    }

    @Test
    void maxResultsCutsTheListAndNamesWhatWasLeftOut() {
        String one = ApiSearch.search("+gts", 1, null);
        assertTrue(one.contains("more match: "), one);
        String many = ApiSearch.search("+gts", ApiSearch.MAX_RESULTS + 100, null);
        assertTrue(many.contains("function gts.oven.bake("), "a huge max is clamped, not refused: " + many);
    }

    @Test
    void anEmptyQueryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> search("   "));
        assertThrows(IllegalArgumentException.class, () -> search(null));
    }

    @Test
    void aPluginWithOnlyAGuideIsListedAndReadable() {
        Handle only = ApiRegistry.registerGuide("gtsg", "Adds slots, no functions.");
        try {
            assertTrue(ApiDocs.namespaces(null).contains("gtsg"));
            assertEquals("Adds slots, no functions.", search("select:gtsg"));
        } finally {
            only.close();
        }
        assertFalse(ApiDocs.namespaces(null).contains("gtsg"));
    }

    @Test
    void aWithdrawnGuideIsGone() {
        Handle other = ApiRegistry.registerGuide("gtsx", "Temporary guide text.");
        Handle group = ApiRegistry.register("gtsx", "tmp", "Temporary.", Oven.class);
        assertTrue(search("select:gtsx").startsWith("Temporary guide text."));
        other.close();
        group.close();
        assertTrue(search("select:gtsx").startsWith("No match"), "the plugin has nothing left to show");
        assertThrows(IllegalArgumentException.class, () -> ApiRegistry.registerGuide("gts", "Second guide."));
    }
}
