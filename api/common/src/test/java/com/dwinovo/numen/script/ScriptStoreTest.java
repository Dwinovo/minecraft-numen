package com.dwinovo.numen.script;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脚本跟着存档走:存下的正文、说明、谁存的,和每份脚本的战绩,存盘读回一模一样;改一份就清掉它的旧战绩;内置脚本登记时把关。
 */
class ScriptStoreTest {

    private static final UUID ARIA = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static ScriptStore roundTrip(ScriptStore store) {
        return ScriptStore.load(store.save(new CompoundTag()));
    }

    @Test
    void savedScriptsAndTheirRecordSurviveARestart() {
        ScriptStore store = new ScriptStore();
        store.put("sweep", new ScriptStore.Saved("-- Sweep.\nwork.dig(...)", "Sweep.", ARIA, "Aria", 1000L));
        store.tally("sweep", true, 0, null, 2000L);
        store.tally("sweep", false, 3, "out of reach", 3000L);
        store.tally("mine", true, 0, null, 4000L);

        ScriptStore back = roundTrip(store);
        assertEquals(store.saved(), back.saved());
        assertEquals(new ScriptStore.Stats(2, 1, 3000L, 3, "out of reach"), back.stats("sweep"));
        assertEquals(new ScriptStore.Stats(1, 1, 4000L, 0, null), back.stats("mine"), "内置脚本的战绩也记");
        assertEquals(0, back.stats("never").runs());
    }

    @Test
    void savingAgainReplacesTheTextAndStartsTheRecordOver() {
        ScriptStore store = new ScriptStore();
        store.put("sweep", new ScriptStore.Saved("-- One.\n", "One.", ARIA, "Aria", 1L));
        store.tally("sweep", true, 0, null, 2L);
        store.put("sweep", new ScriptStore.Saved("-- Two.\n", "Two.", ARIA, "Aria", 3L));
        assertEquals("Two.", store.get("sweep").summary());
        assertEquals(0, store.stats("sweep").runs(), "旧战绩说的是旧正文");

        assertEquals("Two.", store.delete("sweep").summary());
        assertNull(store.get("sweep"));
        assertNull(store.delete("sweep"));
        assertThrows(IllegalArgumentException.class,
                () -> store.put("Bad Name", new ScriptStore.Saved("", "", ARIA, "Aria", 1L)));
    }

    @Test
    void aBuiltInScriptIsCheckedWhenItIsRegistered() {
        BuiltinScripts.register("gt-ok", "-- Does nothing.\nprint(1)");
        assertEquals("Does nothing.", BuiltinScripts.get("gt-ok").summary());

        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class,
                () -> BuiltinScripts.register("gt-ok", "-- Again.\n"));
        assertTrue(twice.getMessage().contains("登记了两次"), twice.getMessage());
        IllegalArgumentException broken = assertThrows(IllegalArgumentException.class,
                () -> BuiltinScripts.register("gt-broken", "-- Broken.\nlocal x = = 1"));
        assertTrue(broken.getMessage().contains("gt-broken:2:"), broken.getMessage());
        IllegalArgumentException silent = assertThrows(IllegalArgumentException.class,
                () -> BuiltinScripts.register("gt-silent", "print(1)"));
        assertTrue(silent.getMessage().contains("注释"), silent.getMessage());
        assertNull(BuiltinScripts.get("gt-broken"));
    }
}
