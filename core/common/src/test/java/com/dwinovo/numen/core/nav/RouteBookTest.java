package com.dwinovo.numen.core.nav;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import com.dwinovo.numen.pathing.plan.Stance;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.search.Route;
import com.dwinovo.numen.pathing.spec.RouteSpec;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/** 路线簿:id 递增、满了淘汰最早的、取走即划掉、记的是那条路线本身、换一本簿子数字接着数。 */
class RouteBookTest {

    /** 身体上那条编号的替身:从 1 起往上数。 */
    private static LongSupplier numbers() {
        return new AtomicLong()::incrementAndGet;
    }

    private static RouteBook.Entry add(RouteBook book, int x) {
        BlockPos start = new BlockPos(x, 64, 0);
        Route route = new Route(start, new Stance(Stance.Kind.GROUND, 64, 63), List.of());
        return book.add(Goals.at(start.east(5)), start.east(5), RouteSpec.defaults(), route, 100L);
    }

    @Test
    void idsCountUpAndLookupFindsThem() {
        RouteBook book = new RouteBook(3, numbers());
        RouteBook.Entry a = add(book, 0);
        RouteBook.Entry b = add(book, 10);
        assertEquals("r1", a.id());
        assertEquals("r2", b.id());
        assertSame(a, book.get("r1"));
        assertSame(b, book.get("r2"));
        assertNull(book.get("r3"));
        assertEquals(2, book.size());
        assertEquals(new BlockPos(10, 64, 0), b.route().start());
        assertEquals(100L, b.createdGameTime());
    }

    @Test
    void capacityEvictsTheOldest() {
        RouteBook book = new RouteBook(2, numbers());
        add(book, 0);
        add(book, 1);
        RouteBook.Entry c = add(book, 2);
        assertEquals(2, book.size());
        assertNull(book.get("r1"));
        assertSame(c, book.get("r3"));
        // 淘汰不回收 id:模型手里的旧 id 不会指到一条新路上
        assertEquals("r4", add(book, 3).id());
    }

    @Test
    void takeRemovesAndSecondTakeFindsNothing() {
        RouteBook book = new RouteBook(3, numbers());
        RouteBook.Entry a = add(book, 0);
        add(book, 1);
        assertSame(a, book.take("r1"));
        assertNull(book.take("r1"));
        assertEquals(1, book.size());
        assertNull(book.take("nope"));
    }

    @Test
    void capacityIsAtLeastOne() {
        RouteBook book = new RouteBook(0, numbers());
        add(book, 0);
        RouteBook.Entry b = add(book, 1);
        assertEquals(1, book.size());
        assertSame(b, book.get("r2"));
    }

    /**
     * 身体重建(休眠回来、重启)时簿子是新的,编号来源是同一条落盘的数:新簿子接着往上数,和团簿共用这条数时也不撞号。
     */
    @Test
    void aRebuiltBookKeepsCountingFromTheSameSource() {
        LongSupplier body = numbers();
        RouteBook before = new RouteBook(3, body);
        add(before, 0);
        add(before, 1);
        body.getAsLong();   // 同一只同伴在别处(团簿)取走了一个号
        RouteBook after = new RouteBook(3, body);
        assertEquals("r4", add(after, 2).id());
        assertNull(after.get("r1"));
    }
}
