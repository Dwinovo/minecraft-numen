package com.dwinovo.numen.gametest;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.FakeConnection;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.inventory.CompanionInventoryMenu;
import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/** Small, deterministic integration checks that exercise a real server level. */
@GameTestHolder("numen")
@PrefixGameTestTemplate(false)
public final class NumenGameTests {
    private static final String EMPTY = "empty";

    private NumenGameTests() { }

    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void placesAndBreaksBlockInServerWorld(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.STONE);
        helper.assertBlockPresent(Blocks.STONE, pos);
        helper.setBlock(pos, Blocks.AIR);
        helper.assertBlockNotPresent(Blocks.STONE, pos);
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 40)
    public static void chestInventoryMovesRealItemStackWithoutDuplication(GameTestHelper helper) {
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, Blocks.CHEST);
        ChestBlockEntity chest = (ChestBlockEntity) helper.getBlockEntity(pos);
        chest.setItem(0, new ItemStack(Items.IRON_INGOT, 8));

        ItemStack moved = chest.removeItem(0, 3);

        helper.assertTrue(moved.getCount() == 3, "removed stack count must be 3");
        helper.assertTrue(chest.getItem(0).getCount() == 5, "remaining stack count must be 5");
        helper.assertTrue(count(chest, Items.IRON_INGOT) + moved.getCount() == 8,
                "inventory transfer must conserve items");
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionMenuQuickMoveIsBidirectionalAndConservesItems(GameTestHelper helper) {
        withCompanion(helper, "ShiftMove", (owner, companion, menu) -> {
            companion.getInventory().setItem(9, new ItemStack(Items.IRON_INGOT, 12));
            int before = count(owner.getInventory(), Items.IRON_INGOT) + count(companion.getInventory(), Items.IRON_INGOT);

            ItemStack movedToOwner = menu.quickMoveStack(owner, 10);
            helper.assertTrue(movedToOwner.getCount() == 12, "companion shift-click should return original stack");
            helper.assertTrue(count(companion.getInventory(), Items.IRON_INGOT) == 0, "companion source must be empty");
            helper.assertTrue(count(owner.getInventory(), Items.IRON_INGOT) == 12, "owner must receive companion stack");

            int ownerMenuSlot = CompanionInventoryMenu.OWNER_SLOT_START;
            ItemStack movedBack = menu.quickMoveStack(owner, ownerMenuSlot);
            helper.assertTrue(movedBack.getCount() == 12, "owner shift-click should return original stack");
            helper.assertTrue(count(owner.getInventory(), Items.IRON_INGOT)
                    + count(companion.getInventory(), Items.IRON_INGOT) == before, "quick move must conserve items");
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionMenuRightClickSplitAndNumberSwapConserveItems(GameTestHelper helper) {
        withCompanion(helper, "Clicks", (owner, companion, menu) -> {
            companion.getInventory().setItem(9, new ItemStack(Items.GOLD_INGOT, 9));
            owner.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
            int goldBefore = count(owner.getInventory(), Items.GOLD_INGOT) + count(companion.getInventory(), Items.GOLD_INGOT);
            int diamondsBefore = count(owner.getInventory(), Items.DIAMOND) + count(companion.getInventory(), Items.DIAMOND);

            menu.clicked(10, 1, ClickType.PICKUP, owner);
            helper.assertTrue(menu.getCarried().getCount() == 5, "right click should carry the larger half");
            helper.assertTrue(companion.getInventory().getItem(9).getCount() == 4, "right click should leave the smaller half");
            menu.clicked(11, 1, ClickType.PICKUP, owner);
            helper.assertTrue(count(owner.getInventory(), Items.GOLD_INGOT)
                    + count(companion.getInventory(), Items.GOLD_INGOT) + menu.getCarried().getCount() == goldBefore,
                    "right-click split must conserve gold");

            menu.clicked(10, 0, ClickType.SWAP, owner);
            helper.assertTrue(companion.getInventory().getItem(9).is(Items.DIAMOND), "number key should move hotbar item into companion slot");
            helper.assertTrue(owner.getInventory().getItem(0).is(Items.GOLD_INGOT), "number key should move companion item into hotbar");
            helper.assertTrue(count(owner.getInventory(), Items.DIAMOND)
                    + count(companion.getInventory(), Items.DIAMOND) == diamondsBefore, "number swap must conserve diamonds");
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionMenuRespectsArmorSlotRules(GameTestHelper helper) {
        withCompanion(helper, "Armor", (owner, companion, menu) -> {
            helper.assertFalse(menu.getSlot(5).mayPlace(new ItemStack(Items.DIRT)), "helmet slot must reject ordinary blocks");
            helper.assertTrue(menu.getSlot(5).mayPlace(new ItemStack(Items.DIAMOND_HELMET)), "helmet slot must accept a helmet");
            helper.assertTrue(menu.getSlot(9).mayPlace(new ItemStack(Items.SHIELD)), "offhand slot must accept a shield");
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void menuCloseReturnsCarriedStackWithoutDuplication(GameTestHelper helper) {
        withCompanion(helper, "CloseCarry", (owner, companion, menu) -> {
            int before = count(owner.getInventory(), Items.EMERALD);
            menu.setCarried(new ItemStack(Items.EMERALD, 7));
            menu.removed(owner);
            helper.assertTrue(menu.getCarried().isEmpty(), "menu close must clear carried stack");
            helper.assertTrue(count(owner.getInventory(), Items.EMERALD) == before + 7,
                    "menu close must return exactly one carried stack");
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionLifecycleSummonsAndDismissesRealPlayerBody(GameTestHelper helper) {
        ServerPlayer owner = owner(helper, "LifecycleOwner");
        NumenPlayer companion = Companions.summon(helper.getLevel().getServer(), owner.getUUID(), unique("Lifecycle"),
                helper.getLevel(), owner.position());
        helper.assertTrue(companion != null && companion.isAlive(), "summon must create a live NumenPlayer");
        helper.assertTrue(NumenPlayer.findByUuid(helper.getLevel().getServer(), companion.getUUID()) == companion,
                "summoned companion must be present in the server player list");
        Companions.dismiss(helper.getLevel().getServer(), companion);
        helper.assertTrue(NumenPlayer.findByUuid(helper.getLevel().getServer(), companion.getUUID()) == null,
                "dismiss must remove the companion body");
        helper.succeed();
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionTwoByTwoCraftingConsumesRealIngredients(GameTestHelper helper) {
        withCompanion(helper, "Craft2x2", (owner, companion, menu) -> {
            for (int slot = 1; slot <= 4; slot++) {
                companion.inventoryMenu.getSlot(slot).set(new ItemStack(Items.OAK_PLANKS));
            }
            companion.inventoryMenu.slotsChanged(companion.inventoryMenu.getSlot(1).container);
            ItemStack result = companion.inventoryMenu.getSlot(0).getItem();
            helper.assertTrue(result.is(Items.CRAFTING_TABLE), "four planks must produce a crafting table");

            companion.inventoryMenu.clicked(0, 0, ClickType.PICKUP, companion);

            helper.assertTrue(companion.inventoryMenu.getCarried().is(Items.CRAFTING_TABLE),
                    "taking the result must put one crafting table on the cursor");
            for (int slot = 1; slot <= 4; slot++) {
                helper.assertTrue(companion.inventoryMenu.getSlot(slot).getItem().isEmpty(),
                        "taking the result must consume every plank");
            }
        });
    }

    @GameTest(template = EMPTY, timeoutTicks = 80)
    public static void companionRemovalInvalidatesOpenMenu(GameTestHelper helper) {
        ServerPlayer owner = owner(helper, "RemovedOwner");
        NumenPlayer companion = Companions.summon(helper.getLevel().getServer(), owner.getUUID(), unique("Removed"),
                helper.getLevel(), owner.position());
        CompanionInventoryMenu menu = new CompanionInventoryMenu(2, owner.getInventory(), companion.getUUID(), companion);
        helper.assertTrue(menu.stillValid(owner), "live owned companion menu must be valid");
        Companions.dismiss(helper.getLevel().getServer(), companion);
        helper.assertFalse(menu.stillValid(owner), "removed companion must invalidate its open menu");
        helper.succeed();
    }

    private static void withCompanion(GameTestHelper helper, String prefix, MenuCheck check) {
        ServerPlayer owner = owner(helper, prefix + "Owner");
        NumenPlayer companion = Companions.summon(helper.getLevel().getServer(), owner.getUUID(), unique(prefix),
                helper.getLevel(), owner.position());
        helper.assertTrue(companion != null, "companion summon failed");
        CompanionInventoryMenu menu = new CompanionInventoryMenu(1, owner.getInventory(), companion.getUUID(), companion);
        try {
            check.run(owner, companion, menu);
            helper.succeed();
        } finally {
            if (NumenPlayer.findByUuid(helper.getLevel().getServer(), companion.getUUID()) != null) {
                Companions.dismiss(helper.getLevel().getServer(), companion);
            }
        }
    }

    private static String unique(String prefix) {
        return (prefix + Long.toUnsignedString(System.nanoTime(), 36));
    }

    private static ServerPlayer owner(GameTestHelper helper, String name) {
        ServerPlayer owner = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(),
                new GameProfile(java.util.UUID.randomUUID(), unique(name)));
        owner.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), new FakeConnection(), owner);
        owner.setPos(helper.getLevel().getSharedSpawnPos().getCenter());
        return owner;
    }

    @FunctionalInterface
    private interface MenuCheck {
        void run(ServerPlayer owner, NumenPlayer companion, CompanionInventoryMenu menu);
    }

    private static int count(Container container, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
