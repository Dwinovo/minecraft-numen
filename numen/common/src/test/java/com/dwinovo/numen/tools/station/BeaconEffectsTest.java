package com.dwinovo.numen.tools.station;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 信标按层数能选的效果。 */
@Tag("mc")
class BeaconEffectsTest {

    @BeforeAll
    static void boot() {
        com.dwinovo.numen.NumenApiFixture.install();
    }

    @Test
    void aBeaconOffersMoreEffectsTheTallerItsPyramid() {
        assertEquals(List.of(), BeaconEffects.names(BeaconEffects.primaries(0)));
        assertEquals(List.of("speed", "haste"), BeaconEffects.names(BeaconEffects.primaries(1)));
        assertEquals(List.of("speed", "haste", "resistance", "jump_boost"), BeaconEffects.names(BeaconEffects.primaries(2)));
        assertEquals(List.of("speed", "haste", "resistance", "jump_boost", "strength"),
                BeaconEffects.names(BeaconEffects.primaries(3)));
        assertEquals(BeaconEffects.primaries(3), BeaconEffects.primaries(4));
    }

    @Test
    void onlyAFourLevelBeaconHasASecondaryEffect() {
        assertEquals(List.of(), BeaconEffects.secondaries(3));
        assertEquals(List.of("regeneration"), BeaconEffects.names(BeaconEffects.secondaries(4)));
    }
}
