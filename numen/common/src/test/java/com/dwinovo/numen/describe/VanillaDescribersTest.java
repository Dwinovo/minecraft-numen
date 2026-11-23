package com.dwinovo.numen.describe;

import com.dwinovo.numen.api.platform.services.BlockStorageReading;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.Energy;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.Exposed;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTank;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.FluidTanks;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemInventory;
import com.dwinovo.numen.api.platform.services.BlockStorageReading.ItemSlot;
import com.dwinovo.numen.tools.Signs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 原版的回答者说的字段。告示牌:两面各四行都在(空行也占位),涂没涂蜡。炉子:燃料还烧几秒、那一件烧到百分之几、还要几秒,不成立的
 * 不写。存储:每个存储一项,带着露出它的面、一共几格、有东西的格;液体每槽的量与容量;能量的存量、上限与进出。
 */
class VanillaDescribersTest {

    @Test
    void bothSidesKeepTheirFourLines() {
        assertEquals(Map.of("front", List.of("Home", "sweet home", "", ""), "back", List.of("", "", "", ""),
                        "waxed", false),
                VanillaDescribers.signFields(new Signs.Text(List.of("Home", "sweet home", "", ""),
                        List.of("", "", "", ""), false)));
    }

    @Test
    void aWaxedSignSaysSo() {
        assertEquals(true, VanillaDescribers.signFields(
                new Signs.Text(List.of("", "", "", ""), List.of("", "", "", ""), true)).get("waxed"));
    }

    @Test
    void aLitFurnaceSaysHowLongItsFuelLastsAndHowFarTheItemIs() {
        assertEquals(Map.of("fuel_seconds", 12, "cook_percent", 40, "cook_seconds_left", 6),
                VanillaDescribers.furnaceFields(240, 80, 200));
        assertEquals(Map.of("fuel_seconds", 1), VanillaDescribers.furnaceFields(5, 0, 200),
                "不满一秒往上取整,没在烧东西就只有燃料那一个字段");
    }

    @Test
    void anIdleFurnaceSaysNothingAndOneWithoutFireHasNoSecondsLeft() {
        assertEquals(Map.of(), VanillaDescribers.furnaceFields(0, 0, 200));
        assertEquals(Map.of(), VanillaDescribers.furnaceFields(0, 0, 0));
        assertEquals(Map.of("cook_percent", 50), VanillaDescribers.furnaceFields(0, 100, 200));
    }

    @Test
    void oneInventoryListsOnlyNonEmptySlotsWithItsSidesAndSize() {
        BlockStorageReading reading = new BlockStorageReading(
                List.of(new Exposed<>(List.of("all", "up"), new ItemInventory(List.of(
                        new ItemSlot("minecraft:diamond", 5), ItemSlot.EMPTY, new ItemSlot("minecraft:stick", 64))))),
                List.of(), null);
        assertEquals(Map.of("inventories", List.of(Map.of("sides", List.of("all", "up"), "slots", 3, "items", List.of(
                        Map.of("slot", 0, "item", "minecraft:diamond", "count", 5L),
                        Map.of("slot", 2, "item", "minecraft:stick", "count", 64L))))),
                VanillaDescribers.storageFields(reading));
    }

    @Test
    void severalStoragesAreSeveralEntriesAndAnEmptyOneHasNoItems() {
        BlockStorageReading reading = new BlockStorageReading(
                List.of(new Exposed<>(List.of("north"), new ItemInventory(List.of(ItemSlot.EMPTY, ItemSlot.EMPTY))),
                        new Exposed<>(List.of("south"), new ItemInventory(List.of(new ItemSlot("minecraft:coal", 1))))),
                List.of(), null);
        assertEquals(Map.of("inventories", List.of(
                        Map.of("sides", List.of("north"), "slots", 2, "items", List.of()),
                        Map.of("sides", List.of("south"), "slots", 1, "items", List.of(
                                Map.of("slot", 0, "item", "minecraft:coal", "count", 1L))))),
                VanillaDescribers.storageFields(reading));
    }

    @Test
    void fluidsAndEnergy() {
        BlockStorageReading reading = new BlockStorageReading(List.of(),
                List.of(new Exposed<>(List.of("all"), new FluidTanks(List.of(
                        new FluidTank("minecraft:water", 4000, 8000), new FluidTank(null, 0, 8000))))),
                new Energy(100, 1000, true, false));
        assertEquals(Map.of(
                        "fluids", List.of(Map.of("sides", List.of("all"), "tanks", List.of(
                                Map.of("fluid", "minecraft:water", "amount_mb", 4000L, "capacity_mb", 8000L),
                                Map.of("amount_mb", 0L, "capacity_mb", 8000L)))),
                        "energy", Map.of("stored_fe", 100L, "capacity_fe", 1000L, "accepts", true, "provides", false)),
                VanillaDescribers.storageFields(reading));
    }

    @Test
    void nothingExposedIsNoAnswer() {
        assertEquals(Map.of(), VanillaDescribers.storageFields(new BlockStorageReading(List.of(), List.of(), null)));
    }
}
