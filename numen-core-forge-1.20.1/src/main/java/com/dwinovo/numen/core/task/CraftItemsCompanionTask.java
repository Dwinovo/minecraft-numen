package com.dwinovo.numen.core.task;

import com.dwinovo.numen.core.pathing.exec.PlayerNav;
import com.dwinovo.numen.core.tools.CraftingPlanner;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Executes a strong crafting plan one irreversible batch at a time. */
public final class CraftItemsCompanionTask implements CompanionTask {
    private static final int STATION_RADIUS = 32;
    private static final double WALK_SPEED = 1.0;
    private static final AbstractContainerMenu DUMMY_MENU = new AbstractContainerMenu((MenuType<?>) null, -1) {
        @Override public ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player, int slot) { return ItemStack.EMPTY; }
        @Override public boolean stillValid(net.minecraft.world.entity.player.Player player) { return true; }
    };

    private final NumenPlayer player;
    private final CraftItemsTaskRecord record;
    private PlayerNav nav;
    private String failReason = "crafting failed";

    public CraftItemsCompanionTask(NumenPlayer player, CraftItemsTaskRecord record) {
        this.player = player;
        this.record = record;
    }

    @Override public void start() {
        if (!clearPersonalCraftingGrid()) {
            record.markFailure(TaskFailureCode.MISSING_ITEM, failReason);
            record.setState(TaskState.FAILED);
            return;
        }
        int current = PlayerInv.count(player.getInventory(), record.target);
        if (record.getBaseline() < 0) record.setBaseline(current);
        record.setProduced(Math.max(record.getProduced(), Math.max(0, current - record.getBaseline())));
        reconcileCheckpoint();
    }

    @Override public TaskState tick() {
        updateProduced();
        if (record.getState() == TaskState.CANCELLED) return TaskState.CANCELLED;
        if (record.getProduced() >= record.count) return TaskState.SUCCESS;
        if (record.getStepIndex() >= record.steps.size()) {
            updateProduced();
            if (record.getProduced() >= record.count) return TaskState.SUCCESS;
            failReason = "crafting plan ended before producing requested target";
            return TaskState.FAILED;
        }

        CraftingPlanner.Step step = record.steps.get(record.getStepIndex());
        if (record.getBatchIndex() >= step.batches()) {
            record.setProgress(record.getStepIndex() + 1, 0);
            record.clearCheckpoint();
            CompanionTickDispatcher.persistNow(player.level.getServer());
            updateProduced();
            if (record.getProduced() >= record.count) return TaskState.SUCCESS;
            return TaskState.RUNNING;
        }

        return switch (step.station()) {
            case PLAYER_CRAFTING, CRAFTING_TABLE -> tickCrafting(step);
            case STONECUTTER -> tickStonecutter(step);
            case FURNACE, BLAST_FURNACE, SMOKER -> tickFurnace(step);
            case CAMPFIRE -> tickCampfire(step);
        };
    }

    private TaskState tickCrafting(CraftingPlanner.Step step) {
        CraftingRecipe recipe = recipe(step, CraftingRecipe.class);
        int size = step.station() == CraftingPlanner.Station.PLAYER_CRAFTING ? 2 : 3;
        if (!recipe.canCraftInDimensions(size, size)) {
            failReason = "recipe no longer fits " + size + "x" + size + " crafting";
            return TaskState.FAILED;
        }
        if (step.station() == CraftingPlanner.Station.CRAFTING_TABLE && !atStation(Blocks.CRAFTING_TABLE)) {
            return travelTo(Blocks.CRAFTING_TABLE);
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CRAFT_COMMIT) {
            if (PlayerInv.count(player.getInventory(), step.output()) >=
                    record.getActionOutputBaseline() + step.outputCount()) {
                advanceBatch();
                return TaskState.RUNNING;
            }
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.CRAFT_COMMIT, record.getStationPos(),
                PlayerInv.count(player.getInventory(), step.output()), 0, 0);
        record.setActionInputBaselines(inventoryBaselines(CraftInventoryPolicy.counts(step.ingredientSlots())));
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return executeCraftingBatch(recipe, step, size) ? advanceBatch() : TaskState.FAILED;
    }

    private boolean executeCraftingBatch(CraftingRecipe recipe, CraftingPlanner.Step step, int size) {
        TransientCraftingContainer grid = new TransientCraftingContainer(DUMMY_MENU, size, size);
        if (!fillCraftingGrid(grid, recipe, step)) return false;
        if (!recipe.matches(grid, player.level)) {
            failReason = "original crafting recipe no longer matches selected ingredients";
            return false;
        }
        ItemStack output = recipe.assemble(grid, player.level.registryAccess());
        if (output.isEmpty() || !output.is(step.output())) {
            failReason = "recipe output changed after planning";
            return false;
        }
        NonNullList<ItemStack> remaining = recipe.getRemainingItems(grid);
        List<ItemStack> additions = new java.util.ArrayList<>();
        for (ItemStack remainder : remaining) if (!remainder.isEmpty()) additions.add(remainder.copy());
        additions.add(output.copy());
        if (!canFitAdditions(additions)) {
            failReason = "inventory needs more free space for crafting output/remainders";
            return false;
        }
        for (int i = 0; i < grid.getContainerSize(); i++) {
            ItemStack used = grid.getItem(i);
            if (!used.isEmpty() && PlayerInv.remove(player.getInventory(), used.getItem(), 1) != 1) {
                failReason = "ingredient disappeared before crafting: " + CraftingPlanner.id(used.getItem());
                return false;
            }
        }
        for (ItemStack remainder : remaining) if (!remainder.isEmpty() && !addToInventory(remainder.copy())) return false;
        return addToInventory(output.copy());
    }

    /** Return stale 2x2 ingredients before an automated recipe; never discard or duplicate them. */
    private boolean clearPersonalCraftingGrid() {
        List<ItemStack> existing = new java.util.ArrayList<>();
        for (int menuSlot = 1; menuSlot <= 4; menuSlot++) {
            ItemStack stack = player.inventoryMenu.getSlot(menuSlot).getItem();
            if (!stack.isEmpty()) existing.add(stack.copy());
        }
        if (!canFitAdditions(existing)) {
            failReason = "cannot clear the 2x2 crafting grid: inventory is full; owner help is required";
            return false;
        }
        for (int menuSlot = 1; menuSlot <= 4; menuSlot++) {
            ItemStack stack = player.inventoryMenu.getSlot(menuSlot).getItem();
            if (stack.isEmpty()) continue;
            ItemStack moving = stack.copy();
            player.getInventory().add(moving);
            if (!moving.isEmpty()) throw new IllegalStateException("crafting-grid preflight disagreed with inventory insertion");
            player.inventoryMenu.getSlot(menuSlot).set(ItemStack.EMPTY);
        }
        player.inventoryMenu.slotsChanged(player.getInventory());
        player.getInventory().setChanged();
        return true;
    }

    private boolean fillCraftingGrid(CraftingContainer grid, CraftingRecipe recipe, CraftingPlanner.Step step) {
        List<Item> selected = step.ingredientSlots();
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (selected.size() != width * height) {
                failReason = "saved shaped recipe layout is invalid";
                return false;
            }
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                Item item = selected.get(y * width + x);
                if (item != Items.AIR) grid.setItem(y * grid.getWidth() + x, new ItemStack(item));
            }
        } else {
            int slot = 0;
            for (Item item : selected) if (item != Items.AIR) grid.setItem(slot++, new ItemStack(item));
        }
        Map<Item, Integer> need = CraftInventoryPolicy.counts(selected);
        for (Map.Entry<Item, Integer> entry : need.entrySet()) {
            if (PlayerInv.count(player.getInventory(), entry.getKey()) < entry.getValue()) {
                failReason = "missing ingredient " + CraftingPlanner.id(entry.getKey());
                return false;
            }
        }
        return true;
    }

    private TaskState tickStonecutter(CraftingPlanner.Step step) {
        StonecutterRecipe recipe = recipe(step, StonecutterRecipe.class);
        if (!atStation(Blocks.STONECUTTER)) return travelTo(Blocks.STONECUTTER);
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CRAFT_COMMIT
                && PlayerInv.count(player.getInventory(), step.output()) >=
                record.getActionOutputBaseline() + step.outputCount()) return advanceBatch();
        Item ingredient = firstIngredient(step);
        SimpleContainer input = new SimpleContainer(new ItemStack(ingredient));
        if (!recipe.matches(input, player.level)) {
            failReason = "stonecutter recipe no longer matches planned input";
            return TaskState.FAILED;
        }
        ItemStack output = recipe.assemble(input, player.level.registryAccess());
        if (output.isEmpty() || !output.is(step.output()) || !canFitAdditions(List.of(output))) {
            failReason = "inventory needs more free space for stonecutter output";
            return TaskState.FAILED;
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.CRAFT_COMMIT, record.getStationPos(),
                PlayerInv.count(player.getInventory(), step.output()), 0, 0);
        record.setActionInputBaselines(inventoryBaselines(Map.of(ingredient, 1)));
        CompanionTickDispatcher.persistNow(player.level.getServer());
        if (PlayerInv.remove(player.getInventory(), ingredient, 1) != 1) {
            failReason = "missing stonecutter input " + CraftingPlanner.id(ingredient);
            return TaskState.FAILED;
        }
        if (!addToInventory(output.copy())) return TaskState.FAILED;
        return advanceBatch();
    }

    private TaskState tickFurnace(CraftingPlanner.Step step) {
        Block block = furnaceBlock(step.station());
        if (!atStation(block)) return travelTo(block);
        BlockEntity entity = player.level.getBlockEntity(record.getStationPos());
        if (!(entity instanceof AbstractFurnaceBlockEntity furnace)) {
            failReason = "furnace block entity is missing";
            return TaskState.FAILED;
        }
        AbstractCookingRecipe recipe = recipe(step, AbstractCookingRecipe.class);
        Item inputItem = firstIngredient(step);

        if (record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_REFUEL) {
            return completeRefuel(furnace, step.station());
        }

        if (record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_WAIT
                || record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_COLLECT) {
            ItemStack result = furnace.getItem(2);
            if (result.is(step.output()) && result.getCount() > record.getMachineOutputBaseline()) {
                record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_COLLECT, record.getStationPos(),
                        record.getActionOutputBaseline(), record.getMachineInputBaseline(), record.getMachineOutputBaseline());
                CompanionTickDispatcher.persistNow(player.level.getServer());
                int produced = result.getCount() - record.getMachineOutputBaseline();
                if (produced < step.outputCount()) return TaskState.RUNNING;
                ItemStack plannedTake = new ItemStack(step.output(), step.outputCount());
                if (!canFitAdditions(List.of(plannedTake))) {
                    failReason = "inventory needs more free space for furnace output";
                    return TaskState.FAILED;
                }
                ItemStack take = result.split(step.outputCount());
                furnace.setChanged();
                if (!addToInventory(take)) return TaskState.FAILED;
                return advanceBatch();
            }
            ItemStack machineInput = furnace.getItem(0);
            if (machineInput.is(inputItem) && machineInput.getCount() > record.getMachineInputBaseline()) {
                boolean lit = player.level.getBlockState(record.getStationPos())
                        .getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
                if (!lit) {
                    ItemStack fuelSlot = furnace.getItem(1);
                    if (fuelSlot.isEmpty()) return beginRefuel(furnace, step);
                    if (net.minecraftforge.common.ForgeHooks.getBurnTime(fuelSlot, recipe.getType()) <= 0) {
                        failReason = "furnace fuel slot contains a non-fuel remainder; clear it and retry";
                        return TaskState.FAILED;
                    }
                }
                return TaskState.RUNNING;
            }
            failReason = "furnace input disappeared without producing the expected output";
            return TaskState.FAILED;
        }

        if (record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_DEPOSIT) {
            ItemStack currentInput = furnace.getItem(0);
            if (currentInput.is(inputItem) && currentInput.getCount() > record.getMachineInputBaseline()) {
                boolean lit = player.level.getBlockState(record.getStationPos())
                        .getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
                if (!lit && furnace.getItem(1).isEmpty()) {
                    Item fuel = record.getMachineFuel();
                    if (fuel != null) {
                        Integer baseline = record.getActionInputBaselines().get(fuel);
                        int inputDebit = fuel == inputItem ? 1 : 0;
                        boolean alreadyRemoved = baseline != null
                                && PlayerInv.count(player.getInventory(), fuel) < baseline - inputDebit;
                        if (!alreadyRemoved && PlayerInv.remove(player.getInventory(), fuel, 1) != 1) {
                            failReason = "selected furnace fuel disappeared";
                            return TaskState.FAILED;
                        }
                        furnace.setItem(1, new ItemStack(fuel));
                        furnace.setChanged();
                    } else {
                        return beginRefuel(furnace, step);
                    }
                }
                record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_WAIT, record.getStationPos(),
                        record.getActionOutputBaseline(), record.getMachineInputBaseline(), record.getMachineOutputBaseline());
                CompanionTickDispatcher.persistNow(player.level.getServer());
                return TaskState.RUNNING;
            }
        }

        ItemStack inputSlot = furnace.getItem(0);
        ItemStack outputSlot = furnace.getItem(2);
        if (!inputSlot.isEmpty()) {
            failReason = "furnace input slot is already in use; use an empty workstation for craft_items";
            return TaskState.FAILED;
        }
        if (!outputSlot.isEmpty() && !outputSlot.is(step.output())) {
            failReason = "furnace output slot is occupied by " + CraftingPlanner.id(outputSlot.getItem());
            return TaskState.FAILED;
        }
        ItemStack test = new ItemStack(inputItem);
        if (!recipe.matches(new SimpleContainer(test), player.level)) {
            failReason = "cooking recipe no longer matches planned input";
            return TaskState.FAILED;
        }
        boolean lit = player.level.getBlockState(record.getStationPos())
                .getValue(net.minecraft.world.level.block.AbstractFurnaceBlock.LIT);
        ItemStack existingFuel = furnace.getItem(1);
        if (!existingFuel.isEmpty()
                && net.minecraftforge.common.ForgeHooks.getBurnTime(existingFuel, recipe.getType()) <= 0
                && !lit) {
            failReason = "furnace fuel slot contains a non-fuel item";
            return TaskState.FAILED;
        }
        boolean needsFuel = !lit && existingFuel.isEmpty();
        Item fuel = needsFuel ? CraftInventoryPolicy.selectFuel(
                player.getInventory().items, recipe.getCookingTime(), step.station()) : null;
        if (needsFuel && fuel == null) {
            failReason = "no suitable furnace fuel in inventory";
            return TaskState.FAILED;
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_DEPOSIT, record.getStationPos(),
                PlayerInv.count(player.getInventory(), step.output()), inputSlot.getCount(), outputSlot.getCount());
        Map<Item, Integer> consumed = new LinkedHashMap<>();
        consumed.merge(inputItem, 1, Integer::sum);
        if (fuel != null) consumed.merge(fuel, 1, Integer::sum);
        record.setActionInputBaselines(inventoryBaselines(consumed));
        record.setMachineFuel(fuel);
        CompanionTickDispatcher.persistNow(player.level.getServer());
        if (PlayerInv.remove(player.getInventory(), inputItem, 1) != 1) {
            failReason = "missing cooking input " + CraftingPlanner.id(inputItem);
            return TaskState.FAILED;
        }
        furnace.setItem(0, new ItemStack(inputItem));
        if (fuel != null) {
            if (PlayerInv.remove(player.getInventory(), fuel, 1) != 1) {
                failReason = "selected fuel disappeared";
                return TaskState.FAILED;
            }
            furnace.setItem(1, new ItemStack(fuel));
        }
        furnace.setChanged();
        record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_WAIT, record.getStationPos(),
                record.getActionOutputBaseline(), record.getMachineInputBaseline(), record.getMachineOutputBaseline());
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return TaskState.RUNNING;
    }

    private TaskState beginRefuel(AbstractFurnaceBlockEntity furnace, CraftingPlanner.Step step) {
        AbstractCookingRecipe recipe = recipe(step, AbstractCookingRecipe.class);
        Item fuel = CraftInventoryPolicy.selectFuel(
                player.getInventory().items, recipe.getCookingTime(), step.station());
        if (fuel == null) {
            failReason = "furnace ran out of fuel before completing the batch";
            return TaskState.FAILED;
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_REFUEL, record.getStationPos(),
                record.getActionOutputBaseline(), record.getMachineInputBaseline(), record.getMachineOutputBaseline());
        record.setMachineFuel(fuel);
        record.setActionInputBaselines(inventoryBaselines(Map.of(fuel, 1)));
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return completeRefuel(furnace, step.station());
    }

    private TaskState completeRefuel(AbstractFurnaceBlockEntity furnace, CraftingPlanner.Station station) {
        Item fuel = record.getMachineFuel();
        if (fuel == null) {
            failReason = "persisted furnace refuel step has no fuel";
            return TaskState.CANCELLED;
        }
        ItemStack slot = furnace.getItem(1);
        if (!slot.isEmpty()) {
            if (!slot.is(fuel)) {
                failReason = "furnace fuel slot changed during refuel";
                return TaskState.FAILED;
            }
        } else {
            int baseline = record.getActionInputBaselines().getOrDefault(fuel,
                    PlayerInv.count(player.getInventory(), fuel));
            boolean removed = PlayerInv.count(player.getInventory(), fuel) < baseline;
            if (!removed && PlayerInv.remove(player.getInventory(), fuel, 1) != 1) {
                failReason = "selected furnace fuel disappeared";
                return TaskState.FAILED;
            }
            furnace.setItem(1, new ItemStack(fuel));
            furnace.setChanged();
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_WAIT, record.getStationPos(),
                record.getActionOutputBaseline(), record.getMachineInputBaseline(), record.getMachineOutputBaseline());
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return TaskState.RUNNING;
    }

    private TaskState tickCampfire(CraftingPlanner.Step step) {
        if (!atStation(Blocks.CAMPFIRE) && !atStation(Blocks.SOUL_CAMPFIRE)) {
            BlockPos pos = record.getStationPos();
            if (pos == null || (!player.level.getBlockState(pos).is(Blocks.CAMPFIRE)
                    && !player.level.getBlockState(pos).is(Blocks.SOUL_CAMPFIRE))) {
                pos = findNearest(Set.of(Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE));
            }
            if (pos == null) { failReason = "no nearby lit campfire within " + STATION_RADIUS + " blocks"; return TaskState.FAILED; }
            return travelTo(pos);
        }
        BlockPos pos = record.getStationPos();
        if (!player.level.getBlockState(pos).getValue(CampfireBlock.LIT)) {
            failReason = "campfire is not lit";
            return TaskState.FAILED;
        }
        BlockEntity entity = player.level.getBlockEntity(pos);
        if (!(entity instanceof CampfireBlockEntity campfire)) {
            failReason = "campfire block entity is missing";
            return TaskState.FAILED;
        }
        CampfireCookingRecipe recipe = recipe(step, CampfireCookingRecipe.class);
        Item input = firstIngredient(step);
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CAMPFIRE_WAIT) {
            boolean stillCooking = countCampfire(campfire, input) > record.getMachineInputBaseline();
            if (stillCooking) return TaskState.RUNNING;
            int inventoryOutput = PlayerInv.count(player.getInventory(), step.output());
            if (inventoryOutput >= record.getActionOutputBaseline() + step.outputCount()) return advanceBatch();
            // Vanilla campfire drops the output entity; walk over the campfire to collect it.
            List<net.minecraft.world.entity.item.ItemEntity> drops = player.level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    new net.minecraft.world.phys.AABB(pos).inflate(2.5),
                    e -> e.getItem().is(step.output()));
            if (!drops.isEmpty()) return travelNear(drops.get(0).blockPosition());
            failReason = "campfire finished but expected output was not found";
            return TaskState.FAILED;
        }
        ItemStack stack = new ItemStack(input);
        if (!recipe.matches(new SimpleContainer(stack), player.level)) {
            failReason = "campfire recipe no longer matches planned input";
            return TaskState.FAILED;
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.CAMPFIRE_PLACE, pos,
                PlayerInv.count(player.getInventory(), step.output()), countCampfire(campfire, input), 0);
        record.setActionInputBaselines(inventoryBaselines(Map.of(input, 1)));
        CompanionTickDispatcher.persistNow(player.level.getServer());
        int slotBefore = PlayerInv.findSlot(player.getInventory(), input);
        if (slotBefore < 0) { failReason = "missing campfire input " + CraftingPlanner.id(input); return TaskState.FAILED; }
        ItemStack held = player.getInventory().getItem(slotBefore);
        if (!campfire.placeFood(player, held, recipe.getCookingTime())) {
            failReason = "campfire has no free cooking slot";
            return TaskState.FAILED;
        }
        record.checkpoint(CraftItemsTaskRecord.Phase.CAMPFIRE_WAIT, pos,
                record.getActionOutputBaseline(), record.getMachineInputBaseline(), 0);
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return TaskState.RUNNING;
    }

    private void reconcileCheckpoint() {
        if (record.getPhase() == CraftItemsTaskRecord.Phase.READY
                || record.getStepIndex() >= record.steps.size()) return;
        CraftingPlanner.Step step = record.steps.get(record.getStepIndex());
        int outputNow = PlayerInv.count(player.getInventory(), step.output());
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CRAFT_COMMIT
                && outputNow >= record.getActionOutputBaseline() + step.outputCount()) {
            advanceBatch();
            return;
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CRAFT_COMMIT
                && inputsWereConsumed()) {
            failReason = "interrupted after crafting inputs were consumed; refusing to repeat the batch";
            record.setState(TaskState.CANCELLED);
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CAMPFIRE_PLACE) {
            BlockPos pos = record.getStationPos();
            BlockEntity entity = pos == null ? null : player.level.getBlockEntity(pos);
            if (entity instanceof CampfireBlockEntity campfire
                    && countCampfire(campfire, firstIngredient(step)) > record.getMachineInputBaseline()) {
                record.checkpoint(CraftItemsTaskRecord.Phase.CAMPFIRE_WAIT, pos,
                        record.getActionOutputBaseline(), record.getMachineInputBaseline(), 0);
            } else if (inputsWereConsumed()) {
                failReason = "interrupted while placing campfire input; refusing to consume it twice";
                record.setState(TaskState.CANCELLED);
            } else {
                record.clearCheckpoint();
            }
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.CAMPFIRE_WAIT) {
            BlockPos pos = record.getStationPos();
            BlockEntity entity = pos == null ? null : player.level.getBlockEntity(pos);
            int inputCount = entity instanceof CampfireBlockEntity campfire
                    ? countCampfire(campfire, firstIngredient(step)) : 0;
            if (inputCount <= record.getMachineInputBaseline()
                    && PlayerInv.count(player.getInventory(), step.output())
                    < record.getActionOutputBaseline() + step.outputCount()
                    && player.level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                            new net.minecraft.world.phys.AABB(pos).inflate(3.0),
                            e -> e.getItem().is(step.output())).isEmpty()) {
                failReason = "campfire output state was lost during restart; refusing to repeat the input";
                record.setState(TaskState.CANCELLED);
            }
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_DEPOSIT) {
            BlockPos pos = record.getStationPos();
            BlockEntity entity = pos == null ? null : player.level.getBlockEntity(pos);
            if (entity instanceof AbstractFurnaceBlockEntity furnace
                    && furnace.getItem(0).getCount() > record.getMachineInputBaseline()) {
                record.checkpoint(CraftItemsTaskRecord.Phase.MACHINE_WAIT, pos,
                        record.getActionOutputBaseline(), record.getMachineInputBaseline(),
                        record.getMachineOutputBaseline());
            } else {
                if (inputsWereConsumed()) {
                    Item input = firstIngredient(step);
                    Integer inputBaseline = record.getActionInputBaselines().get(input);
                    Item fuel = record.getMachineFuel();
                    Integer fuelBaseline = fuel == null ? null : record.getActionInputBaselines().get(fuel);
                    boolean inputConsumed = inputBaseline != null
                            && PlayerInv.count(player.getInventory(), input) < inputBaseline;
                    boolean fuelConsumed = fuelBaseline != null
                            && PlayerInv.count(player.getInventory(), fuel) < fuelBaseline;
                    if (inputConsumed && !fuelConsumed && entity instanceof AbstractFurnaceBlockEntity furnace) {
                        furnace.setItem(0, new ItemStack(input));
                        furnace.setChanged();
                    } else {
                        failReason = "interrupted while depositing furnace inputs; refusing to consume them twice";
                        record.setState(TaskState.CANCELLED);
                    }
                } else {
                    record.clearCheckpoint();
                }
            }
        }
        if (record.getPhase() == CraftItemsTaskRecord.Phase.MACHINE_REFUEL) {
            BlockPos pos = record.getStationPos();
            BlockEntity entity = pos == null ? null : player.level.getBlockEntity(pos);
            if (!(entity instanceof AbstractFurnaceBlockEntity)) {
                failReason = "furnace disappeared during refuel recovery";
                record.setState(TaskState.CANCELLED);
            }
        }
    }

    private TaskState advanceBatch() {
        record.setProgress(record.getStepIndex(), record.getBatchIndex() + 1);
        record.clearCheckpoint();
        stopNav();
        updateProduced();
        CompanionTickDispatcher.persistNow(player.level.getServer());
        return TaskState.RUNNING;
    }

    private void updateProduced() {
        int current = PlayerInv.count(player.getInventory(), record.target);
        record.setProduced(Math.max(record.getProduced(), Math.max(0, current - record.getBaseline())));
    }

    private boolean atStation(Block block) {
        BlockPos pos = record.getStationPos();
        return pos != null && player.level.hasChunkAt(pos)
                && player.level.getBlockState(pos).is(block) && withinUseReach(pos);
    }

    private TaskState travelTo(Block block) {
        BlockPos pos = record.getStationPos();
        if (pos == null || !player.level.hasChunkAt(pos) || !player.level.getBlockState(pos).is(block)) {
            pos = findNearest(Set.of(block));
        }
        if (pos == null) { failReason = "no nearby " + BuiltInRegistries.BLOCK.getKey(block).getPath() + " within " + STATION_RADIUS + " blocks"; return TaskState.FAILED; }
        return travelTo(pos);
    }

    private TaskState travelTo(BlockPos pos) {
        return travelTo(pos, true);
    }

    private TaskState travelTo(BlockPos pos, boolean workstation) {
        if (workstation && (record.getStationPos() == null || !record.getStationPos().equals(pos))) {
            record.checkpoint(record.getPhase(), pos, record.getActionOutputBaseline(),
                    record.getMachineInputBaseline(), record.getMachineOutputBaseline());
            CompanionTickDispatcher.persistNow(player.level.getServer());
        }
        java.util.function.BooleanSupplier reached = workstation
                ? () -> withinUseReach(pos)
                : () -> player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 2.25;
        if (reached.getAsBoolean()) { stopNav(); return TaskState.RUNNING; }
        if (nav == null) nav = new PlayerNav(player, pos, WALK_SPEED, reached);
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> { stopNav(); yield TaskState.RUNNING; }
            case FAILED -> { failReason = "cannot reach workstation at " + pos.toShortString(); stopNav(); yield TaskState.FAILED; }
        };
    }

    private TaskState travelNear(BlockPos goal) {
        return travelTo(goal, false);
    }

    private BlockPos findNearest(Set<Block> blocks) {
        BlockPos center = player.blockPosition();
        BlockPos best = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(center.offset(-STATION_RADIUS, -8, -STATION_RADIUS),
                center.offset(STATION_RADIUS, 8, STATION_RADIUS))) {
            if (!player.level.hasChunkAt(pos) || !blocks.contains(player.level.getBlockState(pos).getBlock())) continue;
            double d = pos.distSqr(center);
            if (d < bestSq) { bestSq = d; best = pos.immutable(); }
        }
        return best;
    }

    private boolean withinUseReach(BlockPos pos) {
        return player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) <= 4.5 * 4.5;
    }

    private boolean addToInventory(ItemStack stack) {
        player.getInventory().add(stack);
        if (!stack.isEmpty()) {
            failReason = "inventory is full; could not store " + CraftingPlanner.id(stack.getItem());
            return false;
        }
        player.getInventory().setChanged();
        return true;
    }

    private boolean canFitAdditions(List<ItemStack> additions) {
        return CraftInventoryPolicy.canFitAdditions(player.getInventory().items, additions);
    }

    private Item firstIngredient(CraftingPlanner.Step step) {
        return CraftInventoryPolicy.firstIngredient(step.ingredientSlots());
    }

    private static int countCampfire(CampfireBlockEntity campfire, Item item) {
        int count = 0;
        for (ItemStack stack : campfire.getItems()) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    private Map<Item, Integer> inventoryBaselines(Map<Item, Integer> inputs) {
        return CraftInventoryPolicy.baselines(inputs.keySet(),
                item -> PlayerInv.count(player.getInventory(), item));
    }

    private boolean inputsWereConsumed() {
        return CraftInventoryPolicy.inputsWereConsumed(record.getActionInputBaselines(),
                item -> PlayerInv.count(player.getInventory(), item));
    }

    private Block furnaceBlock(CraftingPlanner.Station station) {
        return switch (station) {
            case BLAST_FURNACE -> Blocks.BLAST_FURNACE;
            case SMOKER -> Blocks.SMOKER;
            default -> Blocks.FURNACE;
        };
    }

    private <T extends Recipe<?>> T recipe(CraftingPlanner.Step step, Class<T> type) {
        Recipe<?> recipe = player.level.getRecipeManager().byKey(step.recipeId())
                .orElseThrow(() -> new IllegalArgumentException("recipe disappeared: " + step.recipeId()));
        if (!type.isInstance(recipe)) throw new IllegalArgumentException("recipe type changed: " + step.recipeId());
        ItemStack result = recipe.getResultItem(player.level.registryAccess());
        if (!result.is(step.output()) || result.getCount() != step.outputCount()) {
            throw new IllegalArgumentException("recipe output changed: " + step.recipeId());
        }
        return type.cast(recipe);
    }

    private void stopNav() { if (nav != null) { nav.stop(); nav = null; } }

    @Override public boolean monitorsMovementProgress() { return nav != null; }
    @Override public boolean recoverFromStuck() { if (nav == null) return false; stopNav(); return true; }

    @Override public TaskResult buildResult(TaskState finalState) {
        stopNav();
        updateProduced();
        Map<String, Object> data = new HashMap<>();
        data.put("target", CraftingPlanner.id(record.target));
        data.put("requested", record.count);
        data.put("produced", record.getProduced());
        data.put("step_index", record.getStepIndex());
        data.put("steps_total", record.steps.size());
        return switch (finalState) {
            case SUCCESS -> TaskResult.ok("crafted " + record.getProduced() + "/" + record.count + " "
                    + BuiltInRegistries.ITEM.getKey(record.target).getPath(), data);
            case TIMEOUT -> TaskResult.timeout("craft_items timed out", "timeout", data);
            case CANCELLED -> TaskResult.cancelled("craft_items interrupted", "cancelled", data);
            default -> TaskResult.fail(failReason, "crafting_failed", data);
        };
    }
}
