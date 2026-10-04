package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.pathing.body.Controls;
import com.github.ysbbbbbb.kaleidoscopecookery.api.blockentity.IPot;
import com.github.ysbbbbbb.kaleidoscopecookery.block.kitchen.PotBlock;
import com.github.ysbbbbbb.kaleidoscopecookery.blockentity.kitchen.PotBlockEntity;
import com.github.ysbbbbbb.kaleidoscopecookery.init.registry.FoodBiteRegistry;
import com.github.ysbbbbbb.kaleidoscopecookery.init.tag.TagMod;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 炒锅。四档:下料(0)→ 炒(1)→ 出锅(2)→ 糊(3)。
 *
 * <p>节奏全由方块实体自己走,前提是<b>底下那格点着火</b>——没火 {@code tick} 整个不跑,
 * 倒计时也不动。倒油开出 60 秒的下料窗口,挥一下锅铲起锅;炒完只留 40 秒的出锅窗口,
 * 过了就转糊、再 20 秒自己倒成木炭。所以 {@link #oil}、{@link #fill}、{@link #stir}、{@link #plate} 得一口气接着做。
 */
final class PotCooker implements Cooker {

    /**
     * 挥锅铲的间隔(游戏刻)。
     *
     * <p>配方要的是<b>翻够几下</b>,不是翻得多快——在这锅炒完之前翻到就行。按人手的节奏挥,
     * 每刻都挥会把方块实体的同步包打成雨。
     */
    private static final int STIR_INTERVAL = 4;

    private final ServerLevel level;
    private final BlockPos pos;
    private final PotBlockEntity pot;

    PotCooker(ServerLevel level, BlockPos pos, PotBlockEntity pot) {
        this.level = level;
        this.pos = pos;
        this.pot = pot;
    }

    @Override
    public Cookware kind() {
        return Cookware.POT;
    }

    @Override
    public BlockPos pos() {
        return pos;
    }

    private boolean hasOil() {
        return level.getBlockState(pos).getValue(PotBlock.HAS_OIL);
    }

    private List<ItemStack> contents() {
        List<ItemStack> out = new ArrayList<>();
        for (ItemStack stack : pot.getInputs()) {
            if (!stack.isEmpty()) {
                out.add(stack);
            }
        }
        return out;
    }

    @Override
    public KaleidoscopeApi.PotState report() {
        boolean heat = pot.hasHeatSource(level);
        int status = pot.getStatus();
        Integer autoStarts = null;
        Integer done = null;
        Integer burns = null;
        Integer clears = null;
        List<String> needs = new ArrayList<>();
        if (!heat) {
            needs.add("light the block under it — with no heat source nothing moves, not even the countdowns");
        }
        switch (status) {
            case IPot.PUT_INGREDIENT -> {
                if (!hasOil()) {
                    needs.add("pour oil (tag kaleidoscope_cookery:oil) to open the ingredient window");
                } else {
                    autoStarts = pot.getCurrentTick();
                    needs.add("add the ingredients, then hit it once with a kitchen shovel to start");
                }
                if (contents().isEmpty() && hasOil()) {
                    needs.add("the pot is empty — when the window runs out it just clears");
                }
            }
            case IPot.COOKING -> {
                done = pot.getCurrentTick();
                needs.add("keep hitting it with a kitchen shovel — a dish short of its stir-fry count"
                        + " comes out as a mystery dish");
            }
            case IPot.FINISHED -> {
                burns = pot.getCurrentTick();
                needs.add("take it out now" + (pot.hasCarrier() ? " with the right carrier in hand"
                        : " while crouching, with a kitchen shovel in hand"));
            }
            case IPot.BURNT -> {
                clears = pot.getCurrentTick();
                needs.add("already burnt — whatever comes out is dark cuisine; if left alone it turns to charcoal");
            }
            default -> needs.add("unknown stage " + status);
        }
        return new KaleidoscopeApi.PotState(kind(), pos, stage(status), heat, Optional.of(hasOil()), Optional.empty(),
                Optional.empty(), contents().stream().map(s -> Dish.idOf(s.getItem())).toList(),
                status != IPot.PUT_INGREDIENT ? Optional.of(Dish.idOf(pot.getResult().getItem())) : Optional.empty(),
                Optional.ofNullable(autoStarts), Optional.ofNullable(done), Optional.ofNullable(burns),
                Optional.ofNullable(clears), Optional.empty(), needs);
    }

    private static String stage(int status) {
        return switch (status) {
            case IPot.PUT_INGREDIENT -> "put_ingredient";
            case IPot.COOKING -> "cooking";
            case IPot.FINISHED -> "finished";
            case IPot.BURNT -> "burnt";
            default -> "unknown";
        };
    }

    /** 没火的时候锅整个不动,哪一步都白做。 */
    private Step cold() {
        return pot.hasHeatSource(level) ? null : Step.blocked("the pot at " + Cooker.where(pos) + " has no lit heat "
                + "source under it — nothing at all happens until the stove below is lit");
    }

    @Override
    public Step oil(NumenPlayer cook) {
        Step cold = cold();
        if (cold != null) {
            return cold;
        }
        if (pot.getStatus() != IPot.PUT_INGREDIENT) {
            return Step.blocked("the pot at " + Cooker.where(pos) + " is " + stage(pot.getStatus())
                    + ", not waiting for oil");
        }
        if (hasOil()) {
            return Step.done("the pot already has oil in it");
        }
        ItemStack oil = Pantry.find(cook, s -> s.is(TagMod.OIL));
        if (oil.isEmpty()) {
            return Step.blocked("no oil in the inventory (anything in tag kaleidoscope_cookery:oil,"
                    + " or an oil pot) — the pot will not take ingredients without it");
        }
        if (!pot.onPlaceOil(level, cook, oil)) {
            return Step.blocked("the pot would not take " + Dish.idOf(oil.getItem()) + " as oil");
        }
        cook.swing(InteractionHand.MAIN_HAND);
        return Step.done("poured " + Dish.idOf(oil.getItem()) + "; the ingredient window is open for "
                + pot.getCurrentTick() / 20 + " seconds");
    }

    @Override
    public Step base(NumenPlayer cook, Dish dish) {
        return Step.blocked("a pot takes no soup base — that is a stockpot step");
    }

    @Override
    public Step fill(NumenPlayer cook, Dish dish, int[] portions) {
        Step cold = cold();
        if (cold != null) {
            return cold;
        }
        if (pot.getStatus() != IPot.PUT_INGREDIENT) {
            return Step.blocked("the pot at " + Cooker.where(pos) + " is " + stage(pot.getStatus())
                    + " — ingredients go in before it starts cooking");
        }
        if (!hasOil()) {
            return Step.blocked("the pot takes no ingredients before oil: " + "kaleidoscope.pot.oil it first");
        }
        int[] need = dish.stillNeeded(pot.getInputs(), portions);
        for (int i = 0; i < need.length; i++) {
            if (need[i] <= 0) {
                continue;
            }
            Ingredient want = dish.ingredients().get(i);
            ItemStack have = Pantry.find(cook, want);
            if (have.isEmpty()) {
                return Step.blocked("still need " + need[i] + "x " + Dish.names(want)
                        + " and there is none in the inventory");
            }
            String added = Dish.idOf(have.getItem());
            if (!pot.addIngredient(level, cook, have)) {
                return Step.blocked("the pot would not take " + added + " (no free slot, or it is not allowed in a pot)");
            }
            cook.swing(InteractionHand.MAIN_HAND);
            return Step.working("added " + added);
        }
        return Step.done("everything for " + Dish.idOf(dish.result().getItem()) + " is in the pot: "
                + contents().stream().map(s -> Dish.idOf(s.getItem())).toList());
    }

    @Override
    public Step lid(NumenPlayer cook) {
        return Step.blocked("a pot has no lid — that is a stockpot step");
    }

    /** 下料阶段挥一下起锅,炒的阶段按人手的节奏一直挥;炒好了交出去,糊了如实说。 */
    @Override
    public Step stir(NumenPlayer cook) {
        Step cold = cold();
        if (cold != null) {
            return cold;
        }
        return switch (pot.getStatus()) {
            case IPot.PUT_INGREDIENT -> {
                if (contents().isEmpty()) {
                    yield Step.blocked("the pot at " + Cooker.where(pos) + " is empty — nothing to stir-fry");
                }
                yield shovel(cook, "started cooking");
            }
            case IPot.COOKING -> level.getGameTime() % STIR_INTERVAL != 0
                    ? Step.working("stir-frying") : shovel(cook, "stir-frying");
            case IPot.FINISHED -> Step.done("cooked: " + Dish.idOf(pot.getResult().getItem()) + " is ready — plate "
                    + "it within " + pot.getCurrentTick() / 20 + " seconds or it burns");
            case IPot.BURNT -> Step.ruined(ItemStack.EMPTY, "it burnt in the pot");
            default -> Step.blocked("the pot at " + Cooker.where(pos) + " is in stage " + pot.getStatus());
        };
    }

    /** 挥一下锅铲。 */
    private Step shovel(NumenPlayer cook, String doing) {
        ItemStack shovel = Pantry.find(cook, s -> s.is(TagMod.KITCHEN_SHOVEL));
        if (shovel.isEmpty()) {
            return Step.blocked("no kitchen shovel in the inventory — without stir-frying the dish"
                    + " comes out as a mystery dish");
        }
        pot.onShovelHit(level, cook, shovel);
        cook.swing(InteractionHand.MAIN_HAND);
        return Step.working(doing);
    }

    /** 出锅装盘。糊了或者做砸了也照样端出来——锅腾出来,东西也如实交到她手上。 */
    @Override
    public Step plate(NumenPlayer cook, Dish dish) {
        Step cold = cold();
        if (cold != null) {
            return cold;
        }
        if (pot.getStatus() != IPot.FINISHED && pot.getStatus() != IPot.BURNT) {
            return Step.blocked("the pot at " + Cooker.where(pos) + " is " + stage(pot.getStatus())
                    + ", nothing to take out yet");
        }
        boolean burnt = pot.getStatus() == IPot.BURNT;
        ItemStack inPot = pot.getResult();
        boolean ordered = ItemStack.isSameItem(inPot, dish.result());
        // 出锅对不上版时容器是碗:森罗的迷之炒菜那两条路把 carrier 换成了 Items.BOWL
        Ingredient carrier = ordered ? dish.carrier() : Ingredient.of(Items.BOWL);
        ItemStack plated = burnt
                ? FoodBiteRegistry.getItem(FoodBiteRegistry.DARK_CUISINE).getDefaultInstance()
                : inPot.copy();

        if (carrier.isEmpty()) {
            ItemStack shovel = Pantry.find(cook, s -> s.is(TagMod.KITCHEN_SHOVEL));
            if (shovel.isEmpty()) {
                return Step.blocked("this dish needs no carrier, but taking it out needs a kitchen shovel in hand");
            }
            // 不用容器的菜森罗要求蹲着铲(它问的是 isSecondaryUseActive),真玩家也是这么做的。按住潜行键要等身体下一刻的物理步进
            // 才真蹲下,所以这一刻按下、蹲下了那一刻再铲
            if (!cook.isSecondaryUseActive()) {
                cook.controls().set(Controls.Key.SNEAK, true);
                return Step.working("crouching to take it out with the kitchen shovel");
            }
            boolean took = pot.takeOutProduct(level, cook, shovel);
            cook.controls().set(Controls.Key.SNEAK, false);
            return took ? settle(cook, plated, burnt, ordered)
                    : Step.blocked("the pot would not hand the dish over to the kitchen shovel");
        }
        int wanted = burnt ? 1 : inPot.getCount();
        ItemStack vessel = Pantry.find(cook, carrier);
        if (vessel.isEmpty() || vessel.getCount() < wanted) {
            return Step.blocked("need " + wanted + "x " + Dish.names(carrier) + " in the inventory to plate it"
                    + (burnt ? " (it burnt — that is what the burnt dish takes)" : ""));
        }
        return pot.takeOutProduct(level, cook, vessel)
                ? settle(cook, plated, burnt, ordered)
                : Step.blocked("the pot would not hand the dish over with " + Dish.idOf(vessel.getItem()) + " in hand");
    }

    private Step settle(NumenPlayer cook, ItemStack plated, boolean burnt, boolean ordered) {
        cook.swing(InteractionHand.MAIN_HAND);
        if (burnt) {
            return Step.ruined(plated, "it burnt in the pot — what came out is "
                    + Dish.idOf(plated.getItem()) + ", not the dish");
        }
        if (!ordered) {
            return Step.ruined(plated, "what came out is " + Dish.idOf(plated.getItem())
                    + " — the mix or the stir-fry count did not match the recipe");
        }
        return Step.done(plated, "plated " + Dish.idOf(plated.getItem()));
    }
}
