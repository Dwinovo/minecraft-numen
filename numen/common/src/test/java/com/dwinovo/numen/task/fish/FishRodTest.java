package com.dwinovo.numen.task.fish;

import com.dwinovo.numen.FailureType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@code numen.work.fish} 开钓之前:背包里要有鱼竿,没有就当场说缺的是鱼竿;有就用背包里第一根。 */
@Tag("mc")
class FishRodTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void withoutARodTheFailureNamesTheRod() {
        Inventory pack = new Inventory(null);
        pack.setItem(0, new ItemStack(Items.STICK));
        var failure = FishCompanionTask.noRod(pack);
        assertTrue(failure.message().contains("fishing rod"), failure.message());
        assertEquals(FailureType.WRONG_TOOL, failure.type());
    }

    @Test
    void theFirstRodInThePackIsTheOneUsed() {
        Inventory pack = new Inventory(null);
        pack.setItem(7, new ItemStack(Items.FISHING_ROD));
        pack.setItem(20, new ItemStack(Items.FISHING_ROD));
        assertNull(FishCompanionTask.noRod(pack));
        assertEquals(7, FishCompanionTask.findRodSlot(pack));
    }
}
