package com.dwinovo.numen.api.agent.catalog;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 目录:来源按优先级叠加(整条记录取最高的)、常用厂家、随包快照本身。 */
class CatalogTest {

    private static CatalogSource source(String json) {
        Map<String, ProviderInfo> parsed = ModelsDev.parse(json);
        return () -> parsed;
    }

    private static final CatalogSource HIGH = source("""
            {"deepseek":{"name":"DeepSeek (cache)","npm":"@ai-sdk/openai-compatible","api":"https://new.example/v1",
              "models":{"m1":{"name":"M1 new","limit":{"context":2000}}}}}
            """);
    private static final CatalogSource LOW = source("""
            {"deepseek":{"name":"DeepSeek (snapshot)","npm":"@ai-sdk/openai-compatible","api":"https://old.example/v1",
              "models":{"m1":{"name":"M1 old","limit":{"context":1000}},"m2":{"name":"M2 old"}}},
             "kimi":{"name":"Kimi","npm":"@ai-sdk/openai-compatible","models":{}}}
            """);

    @Test
    void theHigherSourceWinsPerRecordWithoutBorrowingFromTheLowerOne() {
        Catalog c = new Catalog(HIGH, LOW);
        assertEquals("DeepSeek (cache)", c.provider("deepseek").name());
        assertEquals("M1 new", c.model("deepseek", "m1").name());
        assertEquals(2000, c.model("deepseek", "m1").contextWindow());
        assertEquals(1, c.provider("deepseek").models().size(), "厂家的型号列表只取最高来源那一份");
    }

    @Test
    void aModelOnlyTheLowerSourceKnowsIsStillFound() {
        Catalog c = new Catalog(HIGH, LOW);
        assertEquals("M2 old", c.model("deepseek", "m2").name());
        assertNotNull(c.provider("kimi"));
    }

    @Test
    void unknownThingsAreNull() {
        Catalog c = new Catalog(HIGH, LOW);
        assertNull(c.provider("nope"));
        assertNull(c.model("deepseek", "nope"));
        assertNull(c.model("nope", "m1"));
        assertNull(c.model("deepseek", null));
    }

    @Test
    void providersListsEveryOneOnceHigherSourceFirst() {
        List<ProviderInfo> all = new Catalog(HIGH, LOW).providers();
        assertEquals(List.of("deepseek", "kimi"), all.stream().map(ProviderInfo::id).toList());
        assertEquals("DeepSeek (cache)", all.get(0).name());
    }

    @Test
    void popularFollowsTheFixedOrderAndSkipsMissing() {
        Catalog c = new Catalog(source("""
                {"xai":{"npm":"@ai-sdk/xai","models":{}},"deepseek":{"npm":"x","models":{}},
                 "obscure":{"npm":"x","models":{}}}
                """));
        assertEquals(List.of("deepseek", "xai"), c.popular().stream().map(ProviderInfo::id).toList());
    }

    @Test
    void theBundledSnapshotCoversTheVendorsWeShip() {
        Catalog c = Catalog.bundled();
        for (String id : VendorDefaults.POPULAR) {
            assertNotNull(c.provider(id), id);
            assertTrue(!c.provider(id).models().isEmpty(), id);
            assertTrue(!c.provider(id).baseUrl().isEmpty(), id + " 要有可用的缺省地址");
        }
        assertEquals(VendorDefaults.POPULAR.size(), c.popular().size());
        assertEquals(Api.RESPONSES, c.provider("openai").api(), "OpenAI 官方的 npm 是 @ai-sdk/openai,走 Responses");
        assertEquals(Api.ANTHROPIC, c.provider("anthropic").api());
    }
}
