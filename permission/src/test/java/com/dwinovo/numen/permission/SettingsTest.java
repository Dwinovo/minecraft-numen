package com.dwinovo.numen.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SettingsTest {

    @Test
    void factoryAsksOnlyForDropAndCommand() {
        for (Verb v : Verb.values()) {
            boolean asks = v == Verb.DROP || v == Verb.COMMAND;
            assertEquals(asks ? Tier.ASK : Tier.ALLOW, Settings.FACTORY.tier(v), v.word());
            assertTrue(Settings.FACTORY.names(v).isEmpty());
        }
    }

    @Test
    void namesKeepTheirOrderAndDoNotRepeat() {
        Settings s = Settings.FACTORY.withName(Verb.BREAK, "minecraft:stone").withName(Verb.BREAK, "minecraft:dirt");
        assertEquals(List.of("minecraft:stone", "minecraft:dirt"), s.names(Verb.BREAK));
        assertSame(s, s.withName(Verb.BREAK, "minecraft:dirt"));
        assertFalse(s.listed(Verb.PLACE, "minecraft:stone"));
        assertEquals(List.of("minecraft:dirt"), s.withoutName(Verb.BREAK, "minecraft:stone").names(Verb.BREAK));
        assertThrows(IllegalArgumentException.class, () -> s.withName(Verb.BREAK, " "));
    }

    @Test
    void presetsOnlyTouchTiersExceptFactory() {
        Settings s = Settings.FACTORY.withName(Verb.COMMAND, "msg");
        Settings observe = s.with(Preset.OBSERVE);
        Settings bypass = s.with(Preset.BYPASS);
        for (Verb v : Verb.values()) {
            assertEquals(Tier.DENY, observe.tier(v));
            assertEquals(Tier.ALLOW, bypass.tier(v));
        }
        assertTrue(observe.listed(Verb.COMMAND, "msg"));
        assertEquals(Settings.FACTORY, observe.with(Preset.FACTORY));
    }

    @Test
    void anEditThatLoosensIsOnlyATierGoingSofterOrANameGoingOnTheList() {
        Settings s = Settings.FACTORY.withName(Verb.BREAK, "a");
        assertTrue(new Edit.SetTier(Verb.DROP, Tier.ALLOW).loosens(s));
        assertFalse(new Edit.SetTier(Verb.DROP, Tier.ASK).loosens(s));
        assertFalse(new Edit.SetTier(Verb.DROP, Tier.DENY).loosens(s));
        assertTrue(new Edit.Add(Verb.BREAK, "b").loosens(s));
        assertFalse(new Edit.Add(Verb.BREAK, "a").loosens(s));
        assertFalse(new Edit.Remove(Verb.BREAK, "a").loosens(s));
    }

    @Test
    void editsApplyToTheSettings() {
        Settings s = new Edit.SetTier(Verb.BREAK, Tier.DENY).applyTo(Settings.FACTORY);
        s = new Edit.Add(Verb.COMMAND, "msg").applyTo(s);
        assertEquals(Tier.DENY, s.tier(Verb.BREAK));
        assertTrue(s.listed(Verb.COMMAND, "msg"));
        s = new Edit.Remove(Verb.COMMAND, "msg").applyTo(s);
        assertFalse(s.listed(Verb.COMMAND, "msg"));
        assertEquals(List.of(), s.entries());
    }
}
