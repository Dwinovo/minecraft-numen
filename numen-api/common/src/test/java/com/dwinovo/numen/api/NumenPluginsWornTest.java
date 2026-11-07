package com.dwinovo.numen.api;

import com.dwinovo.numen.api.gear.GearSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 身体状态打头的 {@code <worn>}:每个位置按先后列出,空位写 empty,同名的几格逗号并列;一个位置都没有就不出这一段。 */
@Tag("mc")
class NumenPluginsWornTest {

    @BeforeAll
    static void boot() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    /** 只用名字和戴着的东西的一格。 */
    private record Slot(String name, ItemStack worn) implements GearSlot {
        @Override
        public Optional<String> refuseWear(ItemStack one) {
            return Optional.empty();
        }

        @Override
        public Optional<String> refuseRemove() {
            return Optional.empty();
        }

        @Override
        public ItemStack swap(ItemStack in) {
            return worn;
        }
    }

    @Test
    void everySlotIsListedEmptyOnesTooAndSameNamedSlotsShareALine() {
        List<GearSlot> slots = List.of(new Slot("head", new ItemStack(Items.IRON_HELMET)), new Slot("chest", ItemStack.EMPTY),
                new Slot("legs", ItemStack.EMPTY), new Slot("feet", ItemStack.EMPTY),
                new Slot("gametest:ring", new ItemStack(Items.AMETHYST_SHARD)), new Slot("gametest:ring", ItemStack.EMPTY),
                new Slot("gametest:charm", ItemStack.EMPTY));
        assertEquals("<worn>head: minecraft:iron_helmet; chest: empty; legs: empty; feet: empty; "
                + "gametest:ring: minecraft:amethyst_shard, empty; gametest:charm: empty</worn>", NumenPlugins.worn(slots));
    }

    @Test
    void noSlotsLeaveTheFragmentOut() {
        assertEquals("", NumenPlugins.worn(List.of()));
    }
}
