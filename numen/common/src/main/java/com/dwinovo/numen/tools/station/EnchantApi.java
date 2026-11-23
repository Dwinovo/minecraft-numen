package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.enchant}:附魔台。看它对一件东西给出的三个选项,照其中一个附魔。物品格放一件要附魔的,青金石格放青金石;选一项是按钮
 * 0 到 2(选项编号减一)。等级、青金石够不够、物品能不能附魔,原版在按钮里判,不够什么也不做:读回来物品没变就是没附上,把要的和有的
 * 说出来。
 */
public final class EnchantApi {

    private static final String KIND = "enchanting table";
    private static final int[] ITEM = {0};
    private static final int[] LAPIS = {1};

    private EnchantApi() {}

    public static void install(NumenApi numen) {
        numen.api("enchant", "An enchanting table: see the three options it offers for an item, enchant the item with "
                + "one of them.", EnchantApi.class);
    }

    /** 附魔台的一个选项。 */
    @Doc("One of the three options an enchanting table offers for an item.")
    public record EnchantChoice(@Doc("Its number, 1 to 3: what numen.enchant.apply takes.") int option,
                                @Doc("Experience levels it asks you to have.") int levels,
                                @Doc("False when the table offers nothing at this option.") boolean offered,
                                @Doc("The one enchantment the table shows for it, minecraft:sharpness; what it gives "
                                        + "may hold more.") Optional<String> enchantment,
                                @Doc("That enchantment's level.") Optional<Integer> enchantmentLevel) {}

    /** 附魔台对一件东西给出的选项。 */
    @Doc("What an enchanting table offers for an item.")
    public record EnchantOptions(List<EnchantChoice> options,
                                 @Doc("Your experience levels.") int levels) {}

    /** 哪张台、哪件东西。 */
    public record Look(@Doc("The enchanting table's cell.") BlockPos enchantingTable,
                       @Doc("The item to enchant, minecraft:diamond_sword: one you carry.") Item item) {}

    @Fn("What an enchanting table offers for an item you carry: its three options and your levels.")
    @Example("for _, o in ipairs(numen.enchant.options({x = 120, y = 64, z = -35}, \"minecraft:diamond_sword\").options) "
            + "do print(o.option, o.levels, o.enchantment) end")
    @Note("Walks up to the table when it is out of reach, puts the item in, reads the options and takes the item back; "
            + "nothing is spent.")
    @SeeAlso("numen.enchant.apply")
    public static Pending<EnchantOptions> options(ServerCall call, Look args) {
        AtomicReference<EnchantOptions> read = new AtomicReference<>();
        return Stations.at(call, args.enchantingTable(), EnchantmentMenu.class, KIND, List.of(
                Move.put(args.item(), 1, ITEM, "the item to enchant"),
                new Deed((menu, her) -> {
                    read.set(read((EnchantmentMenu) menu, her));
                    return null;
                }),
                Move.take(null, null, ITEM, null, false)), read::get);
    }

    /** 哪张台、哪件东西、第几项。 */
    public record Apply(@Doc("The enchanting table's cell.") BlockPos enchantingTable,
                        @Doc("The item to enchant, minecraft:diamond_sword: one you carry.") Item item,
                        @Doc("Which option, 1 to 3, as numen.enchant.options numbers them.") int option) {}

    @Fn("Enchant an item you carry at an enchanting table with one of its three options.")
    @Example("local sword = numen.enchant.apply({x = 120, y = 64, z = -35}, \"minecraft:diamond_sword\", 3)")
    @Note("Walks up to the table when it is out of reach, puts the item and as many lapis lazuli as the option's "
            + "number in, presses the option, and takes the item and any lapis lazuli left back.")
    @Note("Returns the item as it is now. When the table does not enchant it, it fails saying what the option asks "
            + "for and what you have, and the item and the lapis lazuli come back to you.")
    @SeeAlso("numen.enchant.options")
    public static Pending<Stations.Product> apply(ServerCall call, Apply args) {
        int n = args.option();
        String options = Call.of("numen.enchant.options", args.enchantingTable(), args.item());
        if (n < 1 || n > 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "an enchanting table has options 1 to 3; " + n + " is not one",
                    options);
        }
        AtomicReference<Stations.Product> made = new AtomicReference<>();
        return Stations.at(call, args.enchantingTable(), EnchantmentMenu.class, KIND, List.of(
                Move.put(args.item(), 1, ITEM, "the item to enchant"),
                Move.put(Items.LAPIS_LAZULI, n, LAPIS, "payment"),
                new Deed((menu, her) -> {
                    ItemStack now = press((EnchantmentMenu) menu, her, n, options);
                    made.set(Stations.product(now));
                    return "option " + n + " made it " + StackLabel.brief(now);
                }),
                Move.take(null, null, ITEM, null, false),
                Move.take(Items.LAPIS_LAZULI, null, LAPIS, null, true)), made::get);
    }

    private static EnchantOptions read(EnchantmentMenu menu, NumenPlayer her) {
        var ids = her.level().registryAccess().registryOrThrow(Registries.ENCHANTMENT).asHolderIdMap();
        List<EnchantChoice> options = new ArrayList<>();
        for (int i = 0; i < menu.costs.length; i++) {
            Holder<Enchantment> clue = menu.enchantClue[i] < 0 ? null : ids.byId(menu.enchantClue[i]);
            options.add(new EnchantChoice(i + 1, menu.costs[i], menu.costs[i] > 0,
                    clue == null ? Optional.empty() : clue.unwrapKey().map(key -> key.location().toString()),
                    clue == null ? Optional.empty() : Optional.of(menu.levelClue[i])));
        }
        return new EnchantOptions(options, her.experienceLevel);
    }

    /**
     * 按下第 {@code n} 项,交回附好魔的那件;物品没变就是没附上。
     *
     * @param options 看这张台给这件东西哪几项的那一行调用,没附上时当下一步
     */
    private static ItemStack press(EnchantmentMenu menu, NumenPlayer her, int n, String options) {
        ItemStack before = menu.getSlot(ITEM[0]).getItem().copy();
        her.packets().menuButton(n - 1);
        ItemStack now = menu.getSlot(ITEM[0]).getItem();
        if (ItemStack.matches(before, now)) {
            throw refused(menu, her, n, before, options);
        }
        return now.copy();
    }

    /** 没附上:读到的事实——这一项要什么、她和台子里有什么。 */
    private static ApiError refused(EnchantmentMenu menu, NumenPlayer her, int n, ItemStack item, String options) {
        int cost = menu.costs[n - 1];
        if (cost <= 0) {
            return new ApiError(ErrorKind.FAILED, "nothing was enchanted: the table offers nothing at option " + n
                    + " for " + StackLabel.brief(item), options);
        }
        return new ApiError(ErrorKind.FAILED, "nothing was enchanted: option " + n + " asks for " + Math.max(cost, n)
                + " experience levels and " + n + " lapis lazuli; you have " + her.experienceLevel + " levels and put in "
                + menu.getGoldCount() + " lapis lazuli", null);
    }
}
