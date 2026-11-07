package com.dwinovo.numen.task.inventory;

import com.dwinovo.numen.FailureType;
import com.dwinovo.numen.task.base.Precondition;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code numen.inv.eat} 开吃之前就办不成的:创造模式没有饥饿、背包里没有、不是能吃能喝的,按这个次序说第一条;都不是就去吃。 */
@Tag("mc")
class EatRulesTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void creativeModeHasNoHungerSoNothingIsEaten() {
        Precondition.Failure f = EatCompanionTask.refusal(false, 2, Items.BREAD, "bread");
        assertTrue(f.message().contains("creative mode has no hunger") && f.message().contains("kept the bread"), f.message());
    }

    @Test
    void foodNotCarriedSaysWhatIsMissing() {
        Precondition.Failure f = EatCompanionTask.refusal(true, 0, Items.BREAD, "bread");
        assertEquals("no bread in inventory to eat", f.message());
        assertEquals(FailureType.NO_MATERIAL, f.type());
    }

    @Test
    void somethingInedibleSaysItCannotBeEatenOrDrunk() {
        Precondition.Failure f = EatCompanionTask.refusal(true, 3, Items.COBBLESTONE, "cobblestone");
        assertEquals("cobblestone can't be eaten or drunk", f.message());
    }

    @Test
    void carriedFoodOnAHungryBodyIsEaten() {
        assertNull(EatCompanionTask.refusal(true, 1, Items.BREAD, "bread"));
    }
}
