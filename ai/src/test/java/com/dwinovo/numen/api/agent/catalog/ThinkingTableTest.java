package com.dwinovo.numen.api.agent.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.AUTO;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.HIGH;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.LOW;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MAX;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.MEDIUM;
import static com.dwinovo.numen.api.agent.catalog.ThinkingLevel.OFF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 思考档位表:由 models.dev 的 reasoning_options 推出,预算按档位映射且不低于型号下限。 */
class ThinkingTableTest {

    private static ThinkingTable of(String options) {
        return ThinkingTable.parse(true, (JsonArray) JsonParser.parseString(options));
    }

    @Test
    void unknownOrNonReasoningModelsOnlyOfferAuto() {
        assertEquals(List.of(AUTO), ThinkingTable.AUTO_ONLY.levels());
        assertEquals(List.of(AUTO), ThinkingTable.parse(false, null).levels());
        assertEquals(List.of(AUTO), ThinkingTable.parse(false,
                (JsonArray) JsonParser.parseString("[{\"type\":\"toggle\"}]")).levels());
        assertEquals(List.of(AUTO), ThinkingTable.parse(true, null).levels(), "推理型号但没给控制方式");
        assertEquals(List.of(AUTO), of("[]").levels());
    }

    @Test
    void effortValuesMapOntoTheUnifiedLevels() {
        ThinkingTable t = of("[{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"max\"]}]");
        assertEquals(List.of(AUTO, LOW, HIGH, MAX), t.levels(), "没有 none/toggle,关不了");
        assertEquals("low", t.effort(LOW));
        assertEquals("max", t.effort(MAX));
        assertNull(t.effort(MEDIUM));
    }

    @Test
    void minimalAndXhighStandInWhenTheNearerWordIsMissing() {
        ThinkingTable t = of("[{\"type\":\"effort\",\"values\":[\"none\",\"minimal\",\"medium\",\"high\",\"xhigh\"]}]");
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH, MAX), t.levels());
        assertEquals("none", t.effort(OFF));
        assertEquals("minimal", t.effort(LOW));
        assertEquals("xhigh", t.effort(MAX));

        ThinkingTable both = of("[{\"type\":\"effort\",\"values\":[\"minimal\",\"low\",\"high\",\"xhigh\",\"max\"]}]");
        assertEquals("low", both.effort(LOW), "有 low 就不用 minimal");
        assertEquals("max", both.effort(MAX), "有 max 就不用 xhigh");
    }

    @Test
    void toggleAloneIsOnAndOff() {
        ThinkingTable t = of("[{\"type\":\"toggle\"}]");
        assertEquals(List.of(AUTO, OFF, MEDIUM), t.levels(), "“开”记成中档");
        assertTrue(t.toggle());
    }

    @Test
    void toggleAlongsideEffortLetsTheModelBeTurnedOff() {
        ThinkingTable t = of("[{\"type\":\"toggle\"},{\"type\":\"effort\",\"values\":[\"low\",\"high\",\"max\"]}]");
        assertEquals(List.of(AUTO, OFF, LOW, HIGH, MAX), t.levels());
        assertNull(t.effort(OFF), "关是开关管的,没有 effort 取值");
    }

    @Test
    void budgetModelsOfferEveryLevelWithTokenBudgets() {
        ThinkingTable t = of("[{\"type\":\"budget_tokens\",\"min\":1024}]");
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH, MAX), t.levels());
        assertEquals(2_048, t.budgetTokens(LOW));
        assertEquals(8_192, t.budgetTokens(MEDIUM));
        assertEquals(16_384, t.budgetTokens(HIGH));
        assertEquals(32_768, t.budgetTokens(MAX));
        assertEquals(0, t.budgetTokens(OFF));
    }

    @Test
    void budgetsNeverGoBelowTheModelMinimum() {
        ThinkingTable t = of("[{\"type\":\"budget_tokens\",\"min\":4096}]");
        assertEquals(4_096, t.budgetTokens(LOW));
        assertEquals(8_192, t.budgetTokens(MEDIUM));
    }

    @Test
    void effortPlusBudgetKeepsTheEffortLevelsAndTheBudgetsForThem() {
        ThinkingTable t = of("[{\"type\":\"effort\",\"values\":[\"low\",\"medium\",\"high\"]},"
                + "{\"type\":\"budget_tokens\",\"min\":1024}]");
        assertEquals(List.of(AUTO, OFF, LOW, MEDIUM, HIGH), t.levels());
        assertEquals(8_192, t.budgetTokens(MEDIUM));
        assertTrue(!t.effortOnly(), "还有预算写法,不是自适应型");
        assertTrue(of("[{\"type\":\"effort\",\"values\":[\"low\",\"high\"]}]").effortOnly());
    }
}
