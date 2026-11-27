package com.dwinovo.numen.api.seat;

import com.dwinovo.numen.api.agent.skill.SkillRegistry;
import com.dwinovo.numen.api.library.EntryId;
import com.dwinovo.numen.api.library.LibraryHub;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.ClientCall;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.SdkFixture;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.task.reflex.ReflexRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录:只含服务端的注册表拥有的东西,版本随内容变。 */
@Tag("mc")
class SeatCatalogTest {

    private static final UUID HER = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    /** 夹具:一个服务端函数、一个客户端函数。 */
    public static final class Fns {

        private Fns() {}

        public record N(@Doc("A number.") int n) {}

        @Fn("Add one, on the server.")
        @Example("gt.gt_catalog.up(1)")
        public static int up(ServerCall call, N args) {
            return args.n() + 1;
        }

        @Fn("Double, on the owner's client.")
        @Example("gt.gt_catalog.twice(2)")
        public static int twice(ClientCall call, N args) {
            return args.n() * 2;
        }
    }

    @BeforeAll
    static void register(@TempDir Path home) throws Exception {
        SdkFixture.register("gt_catalog", Fns.class);
        Path skill = Files.createDirectories(home.resolve("skills").resolve("gt").resolve("masonry"));
        Files.writeString(skill.resolve(SkillRegistry.SKILL_FILENAME), """
                ---
                name: masonry
                description: Lay stone walls.
                ---

                # Walls
                """);
        Path mute = Files.createDirectories(home.resolve("skills").resolve("gt").resolve("mute"));
        Files.writeString(mute.resolve(SkillRegistry.SKILL_FILENAME), """
                ---
                name: mute
                ---

                # No description, so the model cannot pick it
                """);
        bundle = com.dwinovo.numen.api.library.Shipped.bundle("gt_catalog", home);   // 随发布的技能:目录读的是登记过的来源
    }

    private static java.util.Optional<com.dwinovo.numen.api.agent.scope.Handle> bundle = java.util.Optional.empty();

    @org.junit.jupiter.api.AfterAll
    static void unbundle() {
        bundle.ifPresent(com.dwinovo.numen.api.agent.scope.Handle::close);
    }

    private static List<String> names(JsonElement array, String field) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : (JsonArray) array) {
            out.add(e.getAsJsonObject().get(field).getAsString());
        }
        return out;
    }

    private static List<String> strings(JsonElement array) {
        List<String> out = new ArrayList<>();
        ((JsonArray) array).forEach(e -> out.add(e.getAsString()));
        return out;
    }

    @Test
    void theCatalogHoldsTheNamespacesTheBundledSkillsAndTheInstincts() {
        JsonObject catalog = SeatCatalog.of(HER);
        assertTrue(strings(catalog.get("namespaces")).contains("gt"), catalog.get("namespaces").toString());
        assertEquals(strings(catalog.get("namespaces")).stream().sorted().toList(), strings(catalog.get("namespaces")),
                "the namespaces are sorted, so the line in the prompt is byte-stable");
        assertFalse(catalog.has("api"), "the signatures are not shipped: ApiSearch reads them on the server");
        assertEquals(List.of("masonry"), names(catalog.get("skills"), "name"),
                "bundled skills with a description, whatever the player's switch says");
        assertEquals(ReflexRegistry.descriptions().size(), catalog.getAsJsonArray("instincts").size());
    }

    @Test
    void aNamespaceWithOnlyHiddenGroupsIsNotListed() {
        var handle = ApiRegistry.registerHidden("gth", "gt_catalog_hidden", "Not for the model.", Fns.class);
        try {
            assertFalse(strings(SeatCatalog.of(HER).get("namespaces")).contains("gth"));
        } finally {
            handle.close();
        }
    }

    @Test
    void theVersionIsTheSameWhileNothingChangesAndMovesWhenANamespaceComes() {
        String before = SeatCatalog.of(HER).get("version").getAsString();
        assertEquals(before, SeatCatalog.of(HER).get("version").getAsString());
        var handle = ApiRegistry.register("gtv", "gt_catalog_extra", "Added later.", Fns.class);
        try {
            assertNotEquals(before, SeatCatalog.of(HER).get("version").getAsString());
        } finally {
            handle.close();
        }
        assertEquals(before, SeatCatalog.of(HER).get("version").getAsString(), "withdrawn again: the same catalog");
    }
}
