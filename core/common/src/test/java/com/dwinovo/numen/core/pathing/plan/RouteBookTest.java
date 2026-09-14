package com.dwinovo.numen.core.pathing.plan;

import com.dwinovo.numen.core.pathing.execute.TerrainBill;
import com.dwinovo.numen.core.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.plan.PlanTestSupport.line;
import static com.dwinovo.numen.core.pathing.plan.PlanTestSupport.neverGoal;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 路线簿的钉桩:id 递增、满了淘汰最早的、取走即划掉、起点是路径起点。纯 JVM。 */
class RouteBookTest {

    private static RouteBook.Route add(RouteBook book, int x) {
        return book.add(neverGoal(), RouteSpec.defaults(), line(x, 5, 0), new TerrainBill(), 100L);
    }

    @Test
    void idsCountUpAndLookupFindsThem() {
        RouteBook book = new RouteBook(3);
        RouteBook.Route a = add(book, 0);
        RouteBook.Route b = add(book, 10);
        assertEquals("r1", a.id());
        assertEquals("r2", b.id());
        assertSame(a, book.get("r1"));
        assertSame(b, book.get("r2"));
        assertNull(book.get("r3"));
        assertEquals(2, book.size());
        assertEquals(new BlockPos(10, 64, 0), b.start());
        assertEquals(100L, b.createdGameTime());
    }

    @Test
    void capacityEvictsTheOldest() {
        RouteBook book = new RouteBook(2);
        add(book, 0);
        add(book, 1);
        RouteBook.Route c = add(book, 2);
        assertEquals(2, book.size());
        assertNull(book.get("r1"));
        assertSame(c, book.get("r3"));
        // 淘汰不回收 id:模型手里的旧 id 不会指到一条新路上
        assertEquals("r4", add(book, 3).id());
    }

    @Test
    void takeRemovesAndSecondTakeFindsNothing() {
        RouteBook book = new RouteBook(3);
        RouteBook.Route a = add(book, 0);
        add(book, 1);
        assertSame(a, book.take("r1"));
        assertNull(book.take("r1"));
        assertEquals(1, book.size());
        assertNull(book.take("nope"));
    }

    @Test
    void capacityIsAtLeastOne() {
        RouteBook book = new RouteBook(0);
        add(book, 0);
        RouteBook.Route b = add(book, 1);
        assertEquals(1, book.size());
        assertSame(b, book.get("r2"));
    }
}
