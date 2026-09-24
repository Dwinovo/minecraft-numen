package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IStockpot;
import com.github.ysbbbbbb.kaleidoscopecookery.api.recipe.soupbase.ISoupBase;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.StockpotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.serializer.StockpotRecipeSerializer;
import com.github.ysbbbbbb.kaleidoscopecookery.crafting.soupbase.SoupBaseManager;
import com.github.ysbbbbbb.kaleidoscopecookery.init.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 汤锅。四档:放汤底(0)→ 下料(1)→ 炖(2)→ 盛出(3)。
 *
 * <p>三条硬规矩,顺序全由它们决定:<b>盖着盖子就放不进也盛不出</b>(汤底、下料、出锅都要先揭盖);
 * <b>盖上盖子才会开始炖</b>,而且只要锅里有料、盖子一盖上就立刻开炖——所以料必须在盖盖之前下齐;
 * 没火同样整个不动。汤锅不会糊,炖好了就一直等着,一锅可以盛出好几份。
 */
final class StockpotCooker implements Cooker {

    private final ServerLevel level;
    private final BlockPos pos;
    private final StockpotBlockEntity stockpot;

    StockpotCooker(ServerLevel level, BlockPos pos, StockpotBlockEntity stockpot) {
        this.level = level;
        this.pos = pos;
        this.stockpot = stockpot;
    }

    @Override
    public Cookware kind() {
        return Cookware.STOCKPOT;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    private List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : stockpot.getInputs()) {
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> report() {
        boolean heat = stockpot.hasHeatSource(level);
        boolean lid = stockpot.hasLid();
        int status = stockpot.getStatus();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("cookware", kind().id());
        out.put("pos", Cooker.where(pos));
        out.put("stage", stage(status));
        out.put("has_heat_source", heat);
        out.put("has_lid", lid);
        if (status != IStockpot.PUT_SOUP_BASE) {
            out.put("soup_base", stockpot.getSoupBaseId().toString());
        }
        out.put("in_the_pot", contents().stream().map(s -> Dish.idOf(s.getItem())).toList());
        if (status == IStockpot.COOKING || status == IStockpot.FINISHED) {
            out.put("dish_being_made", Dish.idOf(stockpot.getResult().getItem()));
        }
        List<String> needs = new ArrayList<>();
        if (!heat) {
            needs.add("light the block under it — with no heat source nothing moves");
        }
        switch (status) {
            case IStockpot.PUT_SOUP_BASE -> {
                if (lid) {
                    needs.add("take the lid off first — nothing goes in while it is on");
                }
                needs.add("pour in a soup base (a bucket; plain water is the usual one)");
            }
            case IStockpot.PUT_INGREDIENT -> {
                if (lid) {
                    needs.add("the lid is on, so it will start cooking within a few ticks;"
                            + " take it off to add more");
                } else {
                    needs.add("add every ingredient FIRST, then put the lid on — the lid starts the cooking");
                }
            }
            case IStockpot.COOKING -> needs.add("simmering, wait — it never burns");
            case IStockpot.FINISHED -> {
                out.put("servings_left", stockpot.getTakeoutCount());
                needs.add("take the lid off, then ladle it out with the carrier in hand");
            }
            default -> needs.add("unknown stage " + status);
        }
        out.put("needs", needs);
        return out;
    }

    private static String stage(int status) {
        return switch (status) {
            case IStockpot.PUT_SOUP_BASE -> "put_soup_base";
            case IStockpot.PUT_INGREDIENT -> "put_ingredient";
            case IStockpot.COOKING -> "cooking";
            case IStockpot.FINISHED -> "finished";
            default -> "unknown";
        };
    }

    @Override
    public String cannotStart(Dish dish) {
        if (dish.cookware() != Cookware.STOCKPOT) {
            return dish.id() + " is a " + dish.cookware().id() + " recipe, not a stockpot one";
        }
        int status = stockpot.getStatus();
        if (status == IStockpot.COOKING || status == IStockpot.FINISHED) {
            return "the stockpot at " + Cooker.where(pos) + " is busy (" + stage(status) + ", "
                    + Dish.idOf(stockpot.getResult().getItem()) + ") — " + KaleidoscopeCommands.line(KaleidoscopeCommands.INSPECT)
                    + " it and wait or empty it";
        }
        if (status == IStockpot.PUT_INGREDIENT) {
            if (!contents().isEmpty()) {
                return "the stockpot at " + Cooker.where(pos) + " already has "
                        + contents().stream().map(s -> Dish.idOf(s.getItem())).toList()
                        + " in it — somebody else's mix, take it out first";
            }
            if (!stockpot.getSoupBaseId().equals(dish.soupBase())) {
                return "the stockpot at " + Cooker.where(pos) + " holds soup base "
                        + stockpot.getSoupBaseId() + " but this dish needs " + dish.soupBase()
                        + " — ladle the old base out first";
            }
        }
        return null;
    }

    @Override
    public Step advance(NumenPlayer cook, Dish dish, int[] portions) {
        if (!stockpot.hasHeatSource(level)) {
            return Step.blocked("the stockpot at " + Cooker.where(pos) + " has no lit heat source under it"
                    + " — nothing at all happens until the stove below is lit");
        }
        return switch (stockpot.getStatus()) {
            case IStockpot.PUT_SOUP_BASE -> pourBase(cook, dish);
            case IStockpot.PUT_INGREDIENT -> fill(cook, dish, portions);
            case IStockpot.COOKING -> Step.working("simmering");
            case IStockpot.FINISHED -> ladle(cook, dish);
            default -> Step.blocked("the stockpot at " + Cooker.where(pos) + " is in stage " + stockpot.getStatus());
        };
    }

    private Step pourBase(NumenPlayer cook, Dish dish) {
        if (stockpot.hasLid()) {
            return takeLidOff(cook, "to pour the soup base in");
        }
        ISoupBase base = SoupBaseManager.getSoupBase(dish.soupBase());
        if (base == null) {
            return Step.blocked("this recipe wants soup base " + dish.soupBase() + ", which nothing registers");
        }
        ItemStack bucket = Pantry.find(cook, base::isSoupBase);
        if (bucket.isEmpty()) {
            return Step.blocked("no " + Dish.idOf(base.getDisplayStack().getItem())
                    + " in the inventory for the " + dish.soupBase() + " soup base");
        }
        if (!stockpot.addSoupBase(level, cook, bucket)) {
            return Step.blocked("the stockpot would not take " + Dish.idOf(bucket.getItem()) + " as a soup base");
        }
        cook.swing(InteractionHand.MAIN_HAND);
        return Step.working("poured the " + dish.soupBase() + " soup base");
    }

    private Step fill(NumenPlayer cook, Dish dish, int[] portions) {
        int[] need = dish.stillNeeded(stockpot.getInputs(), portions);
        for (int i = 0; i < need.length; i++) {
            if (need[i] <= 0) {
                continue;
            }
            if (stockpot.hasLid()) {
                return takeLidOff(cook, "to add the rest of the ingredients");
            }
            Ingredient want = dish.ingredients().get(i);
            ItemStack have = Pantry.find(cook, want);
            if (have.isEmpty()) {
                return Step.blocked("still need " + need[i] + "x " + Dish.names(want)
                        + " and there is none in the inventory");
            }
            String added = Dish.idOf(have.getItem());
            if (!stockpot.addIngredient(level, cook, have)) {
                return Step.blocked("the stockpot would not take " + added
                        + " (no free slot, or it is not allowed in a pot)");
            }
            cook.swing(InteractionHand.MAIN_HAND);
            return Step.working("added " + added);
        }
        // 料齐了,盖上盖子——盖上那一刻就开炖
        if (stockpot.hasLid()) {
            return Step.working("lid is on, cooking starts any moment");
        }
        ItemStack lid = Pantry.find(cook, s -> s.is(ModItems.STOCKPOT_LID.get()));
        if (lid.isEmpty()) {
            return Step.blocked("no kaleidoscope_cookery:stockpot_lid in the inventory — without the lid"
                    + " the stockpot never starts cooking");
        }
        if (!stockpot.onLitClick(level, cook, lid)) {
            return Step.blocked("the stockpot would not take the lid");
        }
        cook.swing(InteractionHand.MAIN_HAND);
        return Step.working("lid on, now simmering " + Dish.idOf(dish.result().getItem()));
    }

    private Step ladle(NumenPlayer cook, Dish dish) {
        if (stockpot.hasLid()) {
            return takeLidOff(cook, "to ladle the dish out");
        }
        ItemStack inPot = stockpot.getResult();
        boolean ordered = ItemStack.isSameItem(inPot, dish.result());
        ItemStack plated = inPot.copyWithCount(1);
        // 煮出来对不上版时锅里挂的是"空配方",它认的容器是森罗那份默认容器,不是这道菜写的那个
        Ingredient carrier = ordered ? dish.carrier() : StockpotRecipeSerializer.DEFAULT_CARRIER;
        if (!carrier.isEmpty()) {
            ItemStack vessel = Pantry.find(cook, carrier);
            if (vessel.isEmpty()) {
                return Step.blocked("need " + Dish.names(carrier) + " in the inventory to ladle it out");
            }
            if (!stockpot.takeOutProduct(level, cook, vessel)) {
                return Step.blocked("the stockpot would not hand a serving over with "
                        + Dish.idOf(vessel.getItem()) + " in hand");
            }
        } else if (!stockpot.takeOutProduct(level, cook, ItemStack.EMPTY)) {
            return Step.blocked("the stockpot would not hand a serving over");
        }
        cook.swing(InteractionHand.MAIN_HAND);
        if (!ordered) {
            return Step.ruined(plated, "what came out is " + Dish.idOf(plated.getItem())
                    + " — the mix did not match the recipe");
        }
        return Step.done(plated, "ladled out " + Dish.idOf(plated.getItem())
                + "; " + stockpot.getTakeoutCount() + " serving(s) still in the pot");
    }

    /**
     * 揭盖。森罗把盖子直接塞进主手那一格,所以先空出一格快捷栏握着——不然主手上原来
     * 那件东西会被盖子顶掉,凭空没了。
     */
    private Step takeLidOff(NumenPlayer cook, String what) {
        Inventory inv = cook.getInventory();
        if (!inv.getItem(inv.selected).isEmpty()) {
            int free = -1;
            for (int i = 0; i < Inventory.getSelectionSize(); i++) {
                if (inv.getItem(i).isEmpty()) {
                    free = i;
                    break;
                }
            }
            if (free < 0) {
                return Step.blocked("the hotbar is full, so there is no free hand to take the lid off " + what);
            }
            cook.holdInHand(free);
        }
        if (!stockpot.onLitClick(level, cook, ItemStack.EMPTY)) {
            return Step.blocked("the lid would not come off");
        }
        cook.swing(InteractionHand.MAIN_HAND);
        return Step.working("took the lid off " + what);
    }
}
