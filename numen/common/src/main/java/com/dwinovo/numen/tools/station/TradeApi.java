package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.PlayerInv;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.EntityRef;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MerchantContainer;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.trade}:和村民、流浪商人交易。目标是那只实体。看它的每一笔,或把其中一笔做几次。
 *
 * <p>做一笔是真客户端的那几下:{@code SelectTrade}(原版自己把付款从背包填进付款格),普通地点一下结果格(一下正好一笔;按住 Shift 点是
 * 能做几笔做几笔,所以不用它),再把光标上的东西点进她自己的背包格。付不起、卖空了,原版在结果格出不出这笔的产物里体现:读回来不是这
 * 一笔,就把它要的和她带的说出来。做完把付款格里剩的挪回背包。
 */
public final class TradeApi {

    private static final String KIND = "trader";
    private static final int PAY_A = 0;
    private static final int PAY_B = 1;
    private static final int RESULT = 2;

    private TradeApi() {}

    public static void install(NumenApi numen) {
        numen.api("trade", "Trading with a villager or a wandering trader: see what it offers, make one of its trades.",
                TradeApi.class);
    }

    /** 一笔交易。 */
    @Doc("One trade a villager or wandering trader offers.")
    public record TradeOffer(@Doc("Its number, from 1: what numen.trade.buy takes.") int offer,
                             @Doc("What it asks for: one stack, or two.") List<Stations.Product> asks,
                             @Doc("What it gives.") Stations.Product gives,
                             @Doc("How many times it has been made since the last restock.") int uses,
                             @Doc("How many times it can be made before it is sold out.") int max,
                             @Doc("Sold out: uses reached max.") boolean soldOut) {}

    /** 一只商人的全部交易。 */
    @Doc("Everything a trader offers.")
    public record TradeOffers(List<TradeOffer> offers,
                              @Doc("The villager's level, 1 (novice) to 5 (master); a wandering trader has none.")
                              Optional<Integer> level,
                              @Doc("The villager's experience points.") Optional<Integer> xp) {}

    /** 做成的几笔。 */
    @Doc("What trading got you.")
    public record Bought(@Doc("How many times the trade was made.") int trades,
                         @Doc("What you got, all of them together.") Stations.Product got,
                         @Doc("Why it made fewer than asked: sold out, or out of payment. Absent when it made them "
                                 + "all.") Optional<String> stopped) {}

    /** 哪只商人。 */
    public record Trader(@Doc("The villager or wandering trader: an Entity from numen.scan.entities, or its id "
            + "(villager@3f9a1c).") EntityRef trader) {}

    @Fn("Every trade a villager or wandering trader offers.")
    @Example("for _, o in ipairs(numen.trade.offers(\"villager@3f9a1c\").offers) do print(o.offer, o.gives.item, o.sold_out) end")
    @Note("Walks up to the trader when it is out of reach, reads its trades and steps back out of the trade; nothing "
            + "is bought.")
    @SeeAlso("numen.trade.buy")
    public static Pending<TradeOffers> offers(ServerCall call, Trader args) {
        Entity trader = call.entity(args.trader());
        AtomicReference<TradeOffers> read = new AtomicReference<>();
        return Stations.at(call, args.trader(), MerchantMenu.class, KIND, List.of(new Deed((menu, her) -> {
            read.set(read((MerchantMenu) menu, trader));
            return "read its " + read.get().offers().size() + " trades";
        })), read::get);
    }

    /** 和谁、做哪一笔、做几次。 */
    public record Buy(@Doc("The villager or wandering trader: an Entity from numen.scan.entities, or its id "
            + "(villager@3f9a1c).") EntityRef trader,
                      @Doc("Which trade, by its number in numen.trade.offers.") int offer,
                      @Doc("How many times to make it.") @Omitted("once") @Positional Optional<Integer> times) {}

    @Fn("Make one of a trader's trades, once or several times, paying from your inventory.")
    @Example("numen.trade.buy(\"villager@3f9a1c\", 1)")
    @Example("local b = numen.trade.buy(\"villager@3f9a1c\", 2, 4) print(b.trades, b.got.count)")
    @Note("Walks up to the trader when it is out of reach and makes the trade times times; what you get goes into "
            + "your inventory.")
    @Note("Returns how many trades were made and what you got. It makes fewer than asked, and says why in stopped, "
            + "when the trade sells out or your payment runs out on the way. It fails when not even one can be made, "
            + "saying what the trade asks for and how many you carry, and when your inventory has no room for what "
            + "it gives.")
    @SeeAlso("numen.trade.offers")
    public static Pending<Bought> buy(ServerCall call, Buy args) {
        int n = args.offer();
        if (n < 1) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "trades are numbered from 1; " + n + " is not one",
                    Call.of("numen.trade.offers", args.trader()));
        }
        Integer times = Stations.count("times", args.times());
        AtomicReference<Bought> bought = new AtomicReference<>();
        return Stations.at(call, args.trader(), MerchantMenu.class, KIND, List.of(new Deed((menu, her) ->
                buy((MerchantMenu) menu, her, n, times == null ? 1 : times, Call.of("numen.trade.offers", args.trader()),
                        bought))), bought::get);
    }

    private static TradeOffers read(MerchantMenu menu, Entity trader) {
        MerchantOffers offers = menu.getOffers();
        List<TradeOffer> trades = new ArrayList<>();
        for (int i = 0; i < offers.size(); i++) {
            MerchantOffer offer = offers.get(i);
            List<Stations.Product> asks = new ArrayList<>();
            asks.add(Stations.product(offer.getCostA()));
            if (!offer.getCostB().isEmpty()) {
                asks.add(Stations.product(offer.getCostB()));
            }
            trades.add(new TradeOffer(i + 1, asks, Stations.product(offer.getResult()), offer.getUses(),
                    offer.getMaxUses(), offer.isOutOfStock()));
        }
        return trader instanceof VillagerDataHolder villager
                ? new TradeOffers(trades, Optional.of(villager.getVillagerData().getLevel()), Optional.of(menu.getTraderXp()))
                : new TradeOffers(trades, Optional.empty(), Optional.empty());
    }

    /**
     * 把第 {@code n} 笔做 {@code times} 次,做完的几笔记进 {@code into},交回回执里的那句话。做到中途卖空了、付不起了就停在那儿,已经
     * 做成的算数;一笔都做不成、或背包放不下产物,带着读到的事实收场。
     *
     * @param offers 看这只商人有哪些交易的那一行调用,做不成时当下一步
     */
    private static String buy(MerchantMenu menu, NumenPlayer her, int n, int times, String offers,
                              AtomicReference<Bought> into) {
        if (n > menu.getOffers().size()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "this trader offers " + menu.getOffers().size() + " trades; " + n
                    + " is not one of them", offers);
        }
        MerchantOffer offer = menu.getOffers().get(n - 1);
        MerchantContainer table = (MerchantContainer) menu.getSlot(RESULT).container;
        ItemStack given = offer.getResult().copy();
        int payA = carried(her, offer.getCostA());
        int payB = carried(her, offer.getCostB());
        int made = 0;
        int got = 0;
        String stopped = null;
        try {
            while (made < times) {
                her.packets().selectTrade(n - 1);
                ItemStack result = menu.getSlot(RESULT).getItem();
                if (result.isEmpty() || table.getActiveOffer() != offer) {
                    if (made == 0) {
                        settle(menu, her);
                        throw refused(her, n, offer, offers);
                    }
                    stopped = offer.isOutOfStock() ? "sold out" : "out of payment";
                    break;
                }
                if (room(menu, her, result) < result.getCount()) {
                    throw new ApiError(ErrorKind.FAILED, "your inventory has no room for " + StackLabel.brief(result)
                            + " x" + result.getCount() + (made == 0 ? "" : " (after " + made + " of " + times
                            + " trades, which got you " + got + ")"), null);
                }
                menu.clicked(RESULT, 0, ClickType.PICKUP, her);
                got += menu.getCarried().getCount();
                stow(menu, her);
                made++;
            }
        } finally {
            settle(menu, her);
        }
        into.set(new Bought(made, Stations.product(given, got), Optional.ofNullable(stopped)));
        List<String> paid = new ArrayList<>();
        paid(paid, offer.getCostA(), payA - carried(her, offer.getCostA()));
        paid(paid, offer.getCostB(), payB - carried(her, offer.getCostB()));
        return "made trade " + n + " " + made + " time" + (made == 1 ? "" : "s") + ": paid " + String.join(" and ", paid)
                + ", got " + got + " " + StackLabel.brief(given) + (stopped == null ? "" : "; stopped there: " + stopped);
    }

    /** 这一笔一次都没做成:卖空了,或者她带的付不起——读到的事实。 */
    private static ApiError refused(NumenPlayer her, int n, MerchantOffer offer, String offers) {
        if (offer.isOutOfStock()) {
            return new ApiError(ErrorKind.FAILED, "trade " + n + " is sold out (" + offer.getUses() + " of "
                    + offer.getMaxUses() + " made)", offers);
        }
        List<String> asks = new ArrayList<>();
        for (ItemStack cost : new ItemStack[] {offer.getCostA(), offer.getCostB()}) {
            if (!cost.isEmpty()) {
                asks.add(cost.getCount() + " " + StackLabel.brief(cost) + " (you carry " + carried(her, cost) + ")");
            }
        }
        return new ApiError(ErrorKind.NO_MATERIAL, "trade " + n + " asks for " + String.join(" and ", asks), offers);
    }

    /** 她背着几件这种付款物;这一笔没有这一样付款是 0。 */
    private static int carried(NumenPlayer her, ItemStack cost) {
        return cost.isEmpty() ? 0 : PlayerInv.carriedCount(her.getInventory(), cost.getItem());
    }

    private static void paid(List<String> into, ItemStack cost, int count) {
        if (count > 0) {
            into.add(count + " " + StackLabel.brief(cost));
        }
    }

    /** 她背包里还放得下几件这种东西:空格按一整叠算,同样东西没满的格算它还差的。 */
    private static int room(MerchantMenu menu, NumenPlayer her, ItemStack stack) {
        int room = 0;
        for (Slot slot : menu.slots) {
            if (slot.container != her.getInventory()) {
                continue;
            }
            ItemStack there = slot.getItem();
            if (there.isEmpty()) {
                room += slot.getMaxStackSize(stack);
            } else if (ItemStack.isSameItemSameComponents(there, stack)) {
                room += slot.getMaxStackSize(there) - there.getCount();
            }
        }
        return room;
    }

    /** 光标上的东西点进她的背包:先并进同样东西没满的格,再放空格,像玩家自己一格格点的那样。 */
    private static void stow(MerchantMenu menu, NumenPlayer her) {
        for (boolean merging : new boolean[] {true, false}) {
            for (Slot slot : menu.slots) {
                ItemStack held = menu.getCarried();
                if (held.isEmpty()) {
                    return;
                }
                if (slot.container != her.getInventory()) {
                    continue;
                }
                ItemStack there = slot.getItem();
                boolean fits = merging ? ItemStack.isSameItemSameComponents(there, held)
                        && there.getCount() < slot.getMaxStackSize(there) : there.isEmpty();
                if (fits) {
                    menu.clicked(slot.index, 0, ClickType.PICKUP, her);
                }
            }
        }
    }

    /** 付款格里剩的挪回她的背包(按住 Shift 点它),数她带着多少之前、收场之前都先挪。 */
    private static void settle(MerchantMenu menu, NumenPlayer her) {
        for (int slot : new int[] {PAY_A, PAY_B}) {
            if (menu.getSlot(slot).hasItem()) {
                menu.clicked(slot, 0, ClickType.QUICK_MOVE, her);
            }
        }
    }
}
