package com.dwinovo.numen.task.tasks;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global per-tick budget is the "never stalls the server" promise of both
 * locate tools: drained pools must refuse work, a new tick must replenish,
 * and the three pools must be independent.
 */
class SearchBudgetTest {

    @BeforeEach
    void freshTick() {
        SearchBudget.resetForTick(1);
    }

    @Test
    void checkPoolDrainsAt128() {
        for (int i = 0; i < 128; i++) {
            assertTrue(SearchBudget.tryCheck(), "permit " + i + " should grant");
        }
        assertFalse(SearchBudget.tryCheck(), "pool must be dry after 128");
    }

    @Test
    void chunkLoadPoolDrainsAt2() {
        assertTrue(SearchBudget.tryChunkLoad());
        assertTrue(SearchBudget.tryChunkLoad());
        assertFalse(SearchBudget.tryChunkLoad());
    }

    @Test
    void biomeSamplePoolDrainsAt256() {
        for (int i = 0; i < 256; i++) {
            assertTrue(SearchBudget.tryBiomeSample(), "permit " + i + " should grant");
        }
        assertFalse(SearchBudget.tryBiomeSample());
    }

    @Test
    void sectionScanPoolDrainsAt256() {
        for (int i = 0; i < 256; i++) {
            assertTrue(SearchBudget.trySectionScan(), "permit " + i + " should grant");
        }
        assertFalse(SearchBudget.trySectionScan());
    }

    @Test
    void poolsAreIndependent() {
        while (SearchBudget.tryCheck()) { /* drain checks */ }
        assertTrue(SearchBudget.tryChunkLoad(), "chunk loads survive check drain");
        assertTrue(SearchBudget.tryBiomeSample(), "biome samples survive check drain");
        assertTrue(SearchBudget.trySectionScan(), "section scans survive check drain");
    }

    @Test
    void newTickReplenishes() {
        while (SearchBudget.tryChunkLoad()) { /* drain */ }
        SearchBudget.resetForTick(2);
        assertTrue(SearchBudget.tryChunkLoad());
    }
}
