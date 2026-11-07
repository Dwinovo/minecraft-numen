package com.dwinovo.numen.api.platform;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.Energy;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.Exposed;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTank;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTanks;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemInventory;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemSlot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 给模型看的存储文字长什么样:这一处排版是两个加载器共用的。 */
class BlockStorageReadingTest {

    @Test
    void oneInventoryListsOnlyNonEmptySlots() {
        BlockStorageReading r = new BlockStorageReading(
                List.of(new Exposed<>(List.of("all", "up"), new ItemInventory(List.of(
                        new ItemSlot("minecraft:diamond", 5), ItemSlot.EMPTY, new ItemSlot("minecraft:stick", 64))))),
                List.of(), null);
        assertEquals(List.of(
                "items (sides: all,up), 3 slots:",
                "  slot 0: minecraft:diamond x5",
                "  slot 2: minecraft:stick x64"), r.lines());
    }

    @Test
    void emptyInventoryAndSeveralStoragesAreNumbered() {
        BlockStorageReading r = new BlockStorageReading(
                List.of(new Exposed<>(List.of("north"), new ItemInventory(List.of(ItemSlot.EMPTY, ItemSlot.EMPTY))),
                        new Exposed<>(List.of("south"), new ItemInventory(List.of(new ItemSlot("minecraft:coal", 1))))),
                List.of(), null);
        assertEquals(List.of(
                "items #0 (sides: north), 2 slots:",
                "  (all 2 slots empty)",
                "items #1 (sides: south), 1 slots:",
                "  slot 0: minecraft:coal x1"), r.lines());
    }

    @Test
    void fluidsAndEnergy() {
        BlockStorageReading r = new BlockStorageReading(List.of(),
                List.of(new Exposed<>(List.of("all"), new FluidTanks(List.of(
                        new FluidTank("minecraft:water", 4000, 8000), new FluidTank(null, 0, 8000))))),
                new Energy(100, 1000, true, true));
        assertEquals(List.of(
                "fluids (sides: all):",
                "  tank 0: minecraft:water 4000/8000 mB",
                "  tank 1: empty/8000 mB",
                "energy: 100/1000 FE (accepts/provides)"), r.lines());
    }

    @Test
    void energyThatNeitherAcceptsNorProvidesHasNoSuffix() {
        assertEquals(List.of("energy: 0/10 FE"),
                new BlockStorageReading(List.of(), List.of(), new Energy(0, 10, false, false)).lines());
    }

    @Test
    void nothingExposedIsNoLines() {
        assertEquals(List.of(), new BlockStorageReading(List.of(), List.of(), null).lines());
    }
}
