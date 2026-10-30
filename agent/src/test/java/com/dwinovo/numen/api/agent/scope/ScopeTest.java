package com.dwinovo.numen.api.agent.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopeTest {

    @Test
    void closesInReverseOrderOnce() {
        List<String> order = new ArrayList<>();
        Scope scope = new Scope();
        scope.add(() -> order.add("a"));
        scope.add(() -> order.add("b"));
        scope.close();
        assertEquals(List.of("b", "a"), order);
        scope.close();
        assertEquals(2, order.size(), "a second close has nothing left to undo");
    }

    @Test
    void aHandleAddedAfterCloseIsUndoneAtOnce() {
        List<String> order = new ArrayList<>();
        Scope scope = new Scope();
        scope.close();
        assertThrows(IllegalStateException.class, () -> scope.add(() -> order.add("late")));
        assertEquals(List.of("late"), order);
    }

    @Test
    void oneFailureDoesNotKeepTheRestOpen() {
        List<String> order = new ArrayList<>();
        Scope scope = new Scope();
        scope.add(() -> order.add("a"));
        scope.add(() -> {
            throw new IllegalStateException("first");
        });
        scope.add(() -> order.add("c"));
        IllegalStateException thrown = assertThrows(IllegalStateException.class, scope::close);
        assertEquals("first", thrown.getMessage());
        assertEquals(List.of("c", "a"), order);
        assertEquals(0, scope.size());
    }

    @Test
    void scopeNestsAsAHandle() {
        List<String> order = new ArrayList<>();
        Scope outer = new Scope();
        Scope inner = new Scope();
        inner.add(() -> order.add("inner"));
        assertSame(inner, outer.add(inner));
        outer.close();
        assertEquals(List.of("inner"), order);
    }
}
