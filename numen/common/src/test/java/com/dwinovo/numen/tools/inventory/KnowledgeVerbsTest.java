package com.dwinovo.numen.tools.inventory;

import com.dwinovo.numen.NumenApiFixture;
import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.knowledge.InfoPage;
import com.dwinovo.numen.api.knowledge.KnowledgeKind;
import com.dwinovo.numen.api.knowledge.Recipe;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ApiTester;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.world.item.Item;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 三个知识动词 {@code numen.inv.recipes|uses|info} 把来源的答复合在一起(Lua 模块 {@code inv.lua}):同一条配方出自几个来源合成一条、
 * 注明各个来源;没答上的来源在结果里写明原因与缺的信息,别的照常交回;物品写错是参数错误,不当成来源缺席。来源用夹具登记(经插件的门),
 * 动词从她的入口跑。这里没有身体,{@code numen} 自己的来源(要她的世界)答不上,正好也是一个"缺席"的来源。
 */
@Tag("mc")
class KnowledgeVerbsTest {

    private static boolean booted;

    @BeforeAll
    static void install() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
        if (booted) {
            NumenApiFixture.install();
            NumenPlugins.register("gt_kv_press", numen -> numen.knowledgeSource(KnowledgeKind.RECIPES, "which machine",
                    List.of("chance"), Press.class));
            NumenPlugins.register("gt_kv_lore", numen -> numen.knowledgeSource(KnowledgeKind.INFO, "pack notes",
                    List.of(), Lore.class));
            NumenPlugins.register("gt_kv_gone", numen -> numen.knowledgeSource(KnowledgeKind.RECIPES, "fluids",
                    List.of(), Gone.class));
        }
    }

    public record Query(@Doc("The item.") Item item) {}

    public static final class Press {
        private Press() {}

        @Fn("A source that answers.")
        public static RecipePage read(ServerCall call, Query args) {
            return RecipePage.of(List.of(
                    new Recipe(Optional.of("gt:press_sticks"), "gt:press", Optional.of("Press"), "minecraft:stick", 4,
                            List.of(), List.of("oak_log"), List.of("gt_press"), Optional.empty(), Optional.empty(),
                            Optional.empty(), List.of())));
        }
    }

    public static final class Lore {
        private Lore() {}

        @Fn("A source that answers.")
        public static InfoPage read(ServerCall call, Query args) {
            return new InfoPage(Optional.empty(), List.of("Sticks feed the press."));
        }
    }

    public static final class Gone {
        private Gone() {}

        @Fn("A source that does not answer.")
        public static RecipePage read(ServerCall call, Query args) {
            throw new ApiError(ErrorKind.TIMEOUT, "the client did not answer", null);
        }
    }

    private static JsonObject verb(String code) {
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), code);
        assertTrue(run.ok(), run.message());
        return run.returned().getAsJsonObject();
    }

    private static JsonObject source(JsonObject result, String name) {
        for (var s : result.getAsJsonArray("sources")) {
            if (s.getAsJsonObject().get("source").getAsString().equals(name)) {
                return s.getAsJsonObject();
            }
        }
        throw new AssertionError("no source " + name + " in " + result);
    }

    @Test
    void aSourcesRecipesAreMergedAndTaggedAndAMissingSourceIsNamedWithWhy() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过知识动词钉桩");
        JsonObject result = verb("return numen.inv.recipes(\"minecraft:stick\")");

        JsonArray recipes = result.getAsJsonArray("recipes");
        assertEquals(1, recipes.size(), result.toString());
        JsonObject made = recipes.get(0).getAsJsonObject();
        assertEquals("gt:press", made.get("category").getAsString());
        assertEquals("[\"gt_kv_press\"]", made.getAsJsonArray("sources").toString());
        assertEquals("answered", source(result, "gt_kv_press").get("status").getAsString());
        assertEquals("[\"chance\"]", source(result, "gt_kv_press").getAsJsonArray("not_provided").toString());
        JsonObject gone = source(result, "gt_kv_gone");
        assertEquals("missing", gone.get("status").getAsString());
        assertTrue(gone.get("why").getAsString().contains("timeout"), gone.toString());
        assertEquals("fluids", gone.get("missing").getAsString());
    }

    @Test
    void infoCollectsEverySourcesNotesWithWhoWroteThem() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过知识动词钉桩");
        JsonObject result = verb("return numen.inv.info(\"minecraft:stick\")");

        JsonObject note = result.getAsJsonArray("notes").get(0).getAsJsonObject();
        assertEquals("gt_kv_lore", note.get("source").getAsString());
        assertEquals("Sticks feed the press.", note.get("text").getAsString());
        assertEquals("answered", source(result, "gt_kv_lore").get("status").getAsString());
    }

    @Test
    void aWrongItemIsAnArgumentErrorNotAMissingSource() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过知识动词钉桩");
        ApiTester.Run run = ApiTester.run(null, UUID.randomUUID(), "return numen.inv.recipes(\"minecraft:no_such_item\")");

        assertFalse(run.ok());
        assertTrue(run.message().contains("bad_argument") && run.message().contains("no_such_item"), run.message());
    }
}
