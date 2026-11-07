package com.dwinovo.numen.api.sdk;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实体身份:短码从 UUID 前六位来,写成什么样才读得回,找到几只怎么说。 */
class EntityLabelTest {

    @Test
    void theCodeIsTheFirstSixHexDigitsOfTheUuid() {
        assertEquals("3f9a1c", EntityLabel.code(UUID.fromString("3f9a1c07-4b2e-4d11-9a55-0123456789ab")));
    }

    @Test
    void aMobIsKindAtSixHexDigitsAndAPlayerIsAName() {
        assertTrue(EntityLabel.valid("zombie@3f9a1c"));
        assertTrue(EntityLabel.valid("touhou_little_maid.maid@0a1b2c"));
        assertTrue(EntityLabel.valid("player@ab12cd"), "玩家和别的实体同一种格式");
        assertFalse(EntityLabel.valid("Steve"), "名字不是身份");
        assertFalse(EntityLabel.valid("home"));
        assertFalse(EntityLabel.valid("zombie@3f9a1"), "短码是六位");
        assertFalse(EntityLabel.valid("zombie@3F9A1C"), "短码是小写十六进制");
        assertFalse(EntityLabel.valid("zombie@3f9a1g"));
                assertFalse(EntityLabel.valid(""));
    }

    @Test
    void zeroHitsNoneOneHitFoundSeveralClash() {
        assertInstanceOf(EntityLabel.Match.None.class, EntityLabel.classify(List.of()));
        assertEquals("a", ((EntityLabel.Match.Found<String>) EntityLabel.classify(List.of("a"))).entity());
        var clash = assertInstanceOf(EntityLabel.Match.Ambiguous.class, EntityLabel.classify(List.of("a", "b")));
        assertEquals(List.of("a", "b"), clash.candidates());
    }
}
