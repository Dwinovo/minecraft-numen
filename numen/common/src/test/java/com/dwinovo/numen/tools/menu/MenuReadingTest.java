package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.script.ApiError;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 界面读法里不用起服务器就能钉的逻辑:信标按层数能选的效果,选项编号怎么对到按钮。 */
@Tag("mc")
class MenuReadingTest {

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

    @Test
    void anOptionNumberCountsFromOneAndMustExist() {
        assertEquals(1, new Pick(1, null).numberIn("option", 3));
        assertEquals(3, new Pick(3, null).numberIn("option", 3));
        assertThrows(ApiError.class, () -> new Pick(0, null).numberIn("option", 3));
        assertThrows(ApiError.class, () -> new Pick(4, null).numberIn("option", 3));
        assertThrows(ApiError.class, () -> new Pick(null, "haste").numberIn("option", 3));
    }

    @Test
    void aPickIsAWholeNumberOrAName() {
        assertEquals(new Pick(2, null), Pick.CODEC.decode(2L));
        assertEquals(new Pick(2, null), Pick.CODEC.decode(2.0));
        assertEquals(new Pick(null, "haste"), Pick.CODEC.decode("haste"));
        assertThrows(com.dwinovo.numen.api.sdk.BadValue.class, () -> Pick.CODEC.decode(1.5));
    }
}
