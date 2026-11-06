package com.dwinovo.numen.api;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.knowledge.KnowledgeKind;
import com.dwinovo.numen.api.knowledge.KnowledgeSources;
import com.dwinovo.numen.api.knowledge.RecipePage;
import com.dwinovo.numen.api.sdk.ApiFunction;
import com.dwinovo.numen.api.sdk.ApiRegistry;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.ServerCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 知识来源的登记:一个来源是名字空间里一个隐藏的函数,撤回只撤自己那一条,写得不对的当场拦下、不留半条。 */
class KnowledgeSourcesTest {

    public record Query(@Doc("The item.") String item) {}

    public static final class Server {
        private Server() {}

        @Fn("A source on the server.")
        public static RecipePage read(ServerCall call, Query args) {
            return RecipePage.of(List.of());
        }
    }

    public static final class Two {
        private Two() {}

        @Fn("One.")
        public static RecipePage one(ServerCall call, Query args) {
            return RecipePage.of(List.of());
        }

        @Fn("Two.")
        public static RecipePage two(ServerCall call, Query args) {
            return RecipePage.of(List.of());
        }
    }

    private static List<String> ids(KnowledgeKind kind) {
        return KnowledgeSources.of(kind).stream().map(KnowledgeSources.Source::id).toList();
    }

    @Test
    void aSourceIsAHiddenFunctionAndClosingItRemovesOnlyItself() {
        Handle first = KnowledgeSources.register("ks_one", KnowledgeKind.USES, "machines", List.of("chance"), Server.class);
        Handle second = KnowledgeSources.register("ks_two", KnowledgeKind.USES, "fluids", List.of(), Server.class);
        KnowledgeSources.Source source = KnowledgeSources.of(KnowledgeKind.USES).stream()
                .filter(s -> s.id().equals("ks_one")).findFirst().orElseThrow();
        assertEquals("ks_one.uses_source.read", source.call());
        assertEquals(ApiFunction.Side.SERVER, source.side());
        assertEquals(List.of("chance"), source.notProvided());
        assertTrue(ApiRegistry.group("ks_one.uses_source").hidden(), "a source is not in the model's API index");

        first.close();
        first.close();

        assertEquals(List.of("ks_two"), ids(KnowledgeKind.USES));
        assertNull(ApiRegistry.group("ks_one.uses_source"), "the function goes with the source");
        second.close();
        assertEquals(List.of(), ids(KnowledgeKind.USES));
    }

    @Test
    void aSourceIsExactlyOneFunction() {
        assertThrows(IllegalArgumentException.class,
                () -> KnowledgeSources.register("ks_bad", KnowledgeKind.INFO, "x", List.of(), Two.class));
        assertEquals(List.of(), ids(KnowledgeKind.INFO));
        assertNull(ApiRegistry.group("ks_bad.info_source"), "a refused source leaves no group behind");
    }
}
