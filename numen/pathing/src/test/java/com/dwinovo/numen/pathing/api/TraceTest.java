package com.dwinovo.numen.pathing.api;

import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.Permit;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.SearchClock;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 寻路报给宿主的那一条：一趟怎么收的场、卡在哪、重搜了几次。它只是值，格式化与落盘都在宿主那一侧。 */
class TraceTest {

    private static final Map<String, Integer> ONE_OFF_ROUTE = Map.of("OFF_ROUTE", 1);

    @Test
    void aGiveUpCarriesTheOutcomeTheCellAndTheReason() {
        Trace.PathEvent e = new Trace.PathEvent("NoRoute", new Vec3(1, 64, 2),
                BlockPos.containing(5, 64, 2), "NO_CLEARANCE", 120, 7, ONE_OFF_ROUTE);

        assertEquals("NoRoute", e.outcome(), "结局记的是 Outcome 那一类的名字");
        assertEquals("NO_CLEARANCE", e.reason(), "reason 是枚举的 name()，不是 toString()");
        assertEquals(5, e.cell().getX());
        assertEquals(120, e.ticks());
        assertEquals(7, e.steps());
    }

    @Test
    void theReplanTallyCannotBeChangedAfterTheEventIsMade() {
        Map<String, Integer> live = new HashMap<>(Map.of("TIMEOUT", 2));
        Trace.PathEvent e = new Trace.PathEvent("Arrived", Vec3.ZERO, null, null, 40, 12, live);

        live.put("TIMEOUT", 99);

        assertEquals(2, e.replans().get("TIMEOUT"), "记下的是报的那一刻的账");
        assertThrows(UnsupportedOperationException.class, () -> e.replans().put("FAILED", 1));
    }

    @Test
    void nobodyListeningIsNotAnError() {
        Trace.NONE.path(new Trace.PathEvent("Stopped", Vec3.ZERO, null, null, 1, 0, Map.of()));
    }

    @Test
    void portsWithoutATraceDoNotListen() {
        TerrainPolicy allow = (change, pos, state, view) -> Permit.ALLOW;
        Materials none = Optional::empty;
        Ports ports = new Ports(allow, none, Threats.NONE, SearchClock.STEADY);

        assertTrue(ports.trace() == Trace.NONE);
        assertTrue(ports.terrain() == allow, "四项照旧在原来的位置上");
    }
}
