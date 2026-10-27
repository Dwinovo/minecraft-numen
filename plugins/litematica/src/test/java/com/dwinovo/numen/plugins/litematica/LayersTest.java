package com.dwinovo.numen.plugins.litematica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LayersTest {

    @Test
    void takesLayersFromTheLowestUpWhileTheyFit() {
        assertEquals(2, Layers.fitting(new int[]{6000, 9000, 5000, 100}, 16384));
    }

    @Test
    void aTotalExactlyAtTheLimitFits() {
        assertEquals(3, Layers.fitting(new int[]{8000, 8000, 384}, 16384));
    }

    @Test
    void everyLayerFitsWhenTheTotalIsUnderTheLimit() {
        assertEquals(3, Layers.fitting(new int[]{10, 0, 20}, 16384));
    }

    @Test
    void zeroWhenTheLowestLayerAloneIsTooMany() {
        assertEquals(0, Layers.fitting(new int[]{16385, 1}, 16384));
    }
}
