package com.dwinovo.numen.api.combat;

import com.dwinovo.numen.api.agent.scope.Handle;
import com.dwinovo.numen.api.entity.NumenPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 提供者表:先登记的先认,句柄只撤自己那一个;站位与瞄准的数值规矩。 */
class RangedWeaponsTest {

    /** 认不认得由一个开关定,别的不重要。 */
    private static final class Fake implements RangedWeapon {
        private final String name;
        private final boolean handles;

        Fake(String name, boolean handles) {
            this.name = name;
            this.handles = handles;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean handles(ItemStack stack) {
            return handles;
        }

        @Override
        public Readiness readiness(NumenPlayer body, ItemStack stack) {
            return Readiness.READY;
        }

        @Override
        public Flight flight(NumenPlayer body, ItemStack stack) {
            return new Flight.Hitscan();
        }

        @Override
        public Handling handling(NumenPlayer body, ItemStack stack) {
            return new Handling(1, 2, 3);
        }

        @Override
        public Shot begin(NumenPlayer body, ItemStack stack) {
            return null;
        }

        @Override
        public String misfireText() {
            return "";
        }
    }

    @Test
    void theFirstProviderThatHandlesAnItemWinsAndAHandleRemovesOnlyItsOwn() {
        Fake first = new Fake("first", true);
        Fake second = new Fake("second", true);
        Handle one = RangedWeapons.register(first);
        Handle two = RangedWeapons.register(second);
        try {
            assertEquals("first", RangedWeapons.of(null).orElseThrow().name());
            one.close();
            one.close();
            assertEquals("second", RangedWeapons.of(null).orElseThrow().name());
            assertEquals(1, RangedWeapons.all().stream().filter(w -> w == second).count());
        } finally {
            one.close();
            two.close();
        }
        assertFalse(RangedWeapons.of(null).isPresent());
    }

    @Test
    void aProviderThatHandlesNothingIsSkipped() {
        Handle skipped = RangedWeapons.register(new Fake("deaf", false));
        Handle wanted = RangedWeapons.register(new Fake("wanted", true));
        try {
            assertEquals("wanted", RangedWeapons.of(null).orElseThrow().name());
        } finally {
            skipped.close();
            wanted.close();
        }
    }

    @Test
    void aHandlingNeedsAnOrderedRing() {
        assertThrows(IllegalArgumentException.class, () -> new Handling(5, 3, 10));
        assertThrows(IllegalArgumentException.class, () -> new Handling(1, 8, 5));
        assertEquals(12.0, new Handling(8, 12, 32).outerRing());
    }

    @Test
    void aimIsAlignedWithinAFewDegreesAndADegenerateViewNever() {
        Aim aim = new Aim(new Vec3(0, 0, 10), new Vec3(0, 0, 1), 0.0);
        assertTrue(aim.aligned(new Vec3(0, 0.01, 1)));
        assertFalse(aim.aligned(new Vec3(0, 0.2, 1)), "more than a degree and a half off");
        assertFalse(aim.aligned(Vec3.ZERO));
        assertEquals(Double.MAX_VALUE, Aim.angleDegrees(null, Vec3.ZERO));
        assertEquals(90.0, Aim.angleDegrees(new Vec3(1, 0, 0), new Vec3(0, 1, 0)), 1e-9);
    }
}
