package com.dwinovo.numen.task.reflex;

import com.dwinovo.numen.task.reflex.ReflexRegistry;
import com.dwinovo.numen.task.reflex.PolicyReflex;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure tests for the reflex roster (constitution §6) — no Minecraft: fake
 * {@link PolicyReflex} entries and an in-memory {@link ReflexRegistry.StateStore}.
 * Covers the overview assembly (order, header/tail, disabled entries dropping
 * out), the enabled-switch semantics the chains' getPriority short-circuit reads
 * (default ON, unknown ids fail open), and switch persistence.
 */
class ReflexRegistryTest {

    /** In-memory store standing in for {@code config/numen/reflexes.json}. */
    private static final class MemoryStore implements ReflexRegistry.StateStore {
        final Map<String, Boolean> disk = new HashMap<>();
        int saves;

        @Override public Map<String, Boolean> load() { return new HashMap<>(disk); }

        @Override public void save(Map<String, Boolean> state) {
            disk.clear();
            disk.putAll(state);
            saves++;
        }
    }

    @BeforeEach
    @AfterEach
    void resetRegistry() {
        ReflexRegistry.resetForTest();   // static registry: never leak roster/switches across tests
    }

    // ---- enabled switch: what the chains' short-circuit reads ----

    @Test
    void reflexesDefaultOn() {
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        assertTrue(ReflexRegistry.enabled("mlg"));
    }

    @Test
    void unknownIdFailsOpen() {
        // A check that races registration must not silence an instinct.
        assertTrue(ReflexRegistry.enabled("not_registered"));
    }

    @Test
    void setEnabledFlipsTheSwitch() {
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));
        ReflexRegistry.setEnabled("armor", false);
        assertFalse(ReflexRegistry.enabled("armor"));
        ReflexRegistry.setEnabled("armor", true);
        assertTrue(ReflexRegistry.enabled("armor"));
    }

    @Test
    void registrationIsIdempotentById() {
        ReflexRegistry.register(new PolicyReflex("food", "饿了会自己吃东西"));
        ReflexRegistry.register(new PolicyReflex("food", "第二次注册的自述(应被忽略)"));
        assertTrue(ReflexRegistry.overview().contains("饿了会自己吃东西"));
        assertFalse(ReflexRegistry.overview().contains("应被忽略"));
    }

    // ---- overview assembly ----

    @Test
    void overviewJoinsDescriptionsInRegistrationOrder() {
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));
        String overview = ReflexRegistry.overview();
        assertTrue(overview.startsWith("你的身体有这些本能"));
        int mlg = overview.indexOf("会用水桶自救高坠");
        int armor = overview.indexOf("会自动穿上更好的盔甲");
        assertTrue(mlg >= 0 && armor > mlg);
        assertTrue(overview.contains("你的显式动作永远优先"));
    }

    @Test
    void disabledReflexDropsOutOfTheOverview() {
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));
        ReflexRegistry.setEnabled("armor", false);
        String overview = ReflexRegistry.overview();
        assertTrue(overview.contains("会用水桶自救高坠"));
        assertFalse(overview.contains("会自动穿上更好的盔甲"));
    }

    @Test
    void emptyRosterHasEmptyOverview() {
        assertEquals("", ReflexRegistry.overview());
    }

    @Test
    void allDisabledHasEmptyOverview() {
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.setEnabled("mlg", false);
        assertEquals("", ReflexRegistry.overview());
    }

    // ---- persistence ----

    @Test
    void bindStoreAppliesSavedOverrides() {
        MemoryStore store = new MemoryStore();
        store.disk.put("armor", false);
        ReflexRegistry.bindStore(store);
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));
        assertFalse(ReflexRegistry.enabled("armor"));   // the owner's saved OFF survives restart
    }

    @Test
    void setEnabledPersistsTheFullSwitchTable() {
        MemoryStore store = new MemoryStore();
        ReflexRegistry.bindStore(store);
        ReflexRegistry.register(new PolicyReflex("mlg", "会用水桶自救高坠"));
        ReflexRegistry.register(new PolicyReflex("armor", "会自动穿上更好的盔甲"));

        ReflexRegistry.setEnabled("armor", false);

        assertEquals(1, store.saves);
        assertEquals(Boolean.TRUE, store.disk.get("mlg"));     // defaults written explicitly
        assertEquals(Boolean.FALSE, store.disk.get("armor"));
    }
}
