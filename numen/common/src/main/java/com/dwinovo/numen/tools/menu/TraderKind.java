package com.dwinovo.numen.tools.menu;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.permission.Action;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.tools.inventory.InvApi.Stack;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.Merchant;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 村民与流浪商人的交易界面:每一笔的付出(一件或两件)、得到、已用次数与上限、是不是卖空了,村民的等级与经验;选一笔就是
 * {@code SelectTrade}(编号减一),原版自己把付款从背包填进付款格,选完只选不取——结果格的东西用 {@code numen.gui.take} 或
 * {@code numen.gui.quick} 取,和从别的容器里拿东西一样。材料够不够、次数用没用完,原版在结果格出不出东西里体现,读回来结果格没有
 * 这笔的产物就是没成,把要的和带的说出来。
 */
final class TraderKind implements MenuKind.Chooses<MerchantMenu> {

    @Override
    public Class<MerchantMenu> menu() {
        return MerchantMenu.class;
    }

    @Override
    public String name() {
        return "a villager's trade window";
    }

    @Override
    public Screen read(MerchantMenu menu, NumenPlayer her) {
        MerchantOffers offers = menu.getOffers();
        List<Screen.Trade> trades = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            List<Stack> pay = new ArrayList<>();
            pay.add(stack(offer.getCostA()));
            if (!offer.getCostB().isEmpty()) {
                pay.add(stack(offer.getCostB()));
            }
            trades.add(new Screen.Trade(i + 1, pay, stack(offer.getResult()), offer.getUses(), offer.getMaxUses(),
                    offer.isOutOfStock()));
        }
        Entity trader = merchant(her);
        return trader instanceof VillagerDataHolder villager
                ? new Screen.Trading(trades, Optional.of(villager.getVillagerData().getLevel()),
                        Optional.of(menu.getTraderXp()))
                : new Screen.Trading(trades, Optional.empty(), Optional.empty());
    }

    /** 用这只商人的交易,是对一只实体的"用":权限层按它裁决。 */
    @Override
    public Action gate(MerchantMenu menu, NumenPlayer her) {
        Entity trader = merchant(her);
        return trader == null ? null : Action.useEntity(trader);
    }

    @Override
    public String choose(MerchantMenu menu, NumenPlayer her, Pick first, Pick second) {
        if (second != null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a trade window takes one trade number", Call.of("numen.gui.view"));
        }
        int n = first.numberIn("trade", menu.getOffers().size());
        MerchantOffer offer = menu.getOffers().get(n - 1);
        her.packets().selectTrade(n - 1);
        if (!ItemStack.isSameItemSameComponents(menu.getSlot(2).getItem(), offer.getResult())) {
            throw refused(her, n, offer);
        }
        return "selected trade " + n + ": the result slot (window slot 2) holds " + Receipt.stack(menu.getSlot(2).getItem());
    }

    /** 这笔没备好:卖空了,或者她带的付不起——读到的事实。 */
    private static ApiError refused(NumenPlayer her, int n, MerchantOffer offer) {
        if (offer.isOutOfStock()) {
            return new ApiError(ErrorKind.FAILED, "trade " + n + " is sold out (" + offer.getUses() + " of "
                    + offer.getMaxUses() + " used)", Call.of("numen.gui.view"));
        }
        List<String> asks = new ArrayList<>();
        for (ItemStack cost : new ItemStack[] {offer.getCostA(), offer.getCostB()}) {
            if (!cost.isEmpty()) {
                asks.add(Receipt.stack(cost) + " (you carry " + PlayerInv.carriedCount(her.getInventory(), cost.getItem())
                        + ")");
            }
        }
        return new ApiError(ErrorKind.FAILED, "trade " + n + " is not ready: it asks for " + String.join(" and ", asks),
                Call.of("numen.gui.view"));
    }

    private static Stack stack(ItemStack stack) {
        return Stack.of(stack, stack.getCount());
    }

    /** 此刻在和她交易的那只商人(在她附近的实体里找):原版把"谁在和谁交易"记在商人身上。 */
    private static Entity merchant(NumenPlayer her) {
        for (Entity each : her.level().getEntitiesOfClass(Entity.class, her.getBoundingBox().inflate(16.0),
                e -> e instanceof Merchant m && m.getTradingPlayer() == her)) {
            return each;
        }
        return null;
    }
}
