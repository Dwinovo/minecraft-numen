package com.dwinovo.numen.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.entity.NumenPlayer;
import com.dwinovo.numen.api.entity.StackLabel;
import com.dwinovo.numen.api.entity.Stacks;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.task.station.StationTaskRecord.Move;
import com.dwinovo.numen.task.station.StationTaskRecord.Step;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@code numen.anvil}:铁砧。把一件东西和另一件合在一起(修、并附魔),或给它改名。两件各进各的格,名字是 {@code RenameItem};结果与要花的
 * 经验原版在放进去、改名的那一刻算好。取结果之前先读:没有结果,或经验不够,就说它要多少、她有多少——经验不够时原版不让取,点下去
 * 什么也不发生。
 */
public final class AnvilApi {

    private static final String KIND = "anvil";
    private static final int[] ITEM = {AnvilMenu.INPUT_SLOT};
    private static final int[] OTHER = {AnvilMenu.ADDITIONAL_SLOT};
    private static final int[] RESULT = {AnvilMenu.RESULT_SLOT};

    private AnvilApi() {}

    public static void install(NumenApi numen) {
        numen.api("anvil", "An anvil: combine an item with another (repair it, add enchantments), or rename it.",
                AnvilApi.class);
    }

    /** 铁砧做出来的。 */
    @Doc("What an anvil made.")
    public record AnvilWork(@Doc("The item as it is now.") Stations.Product item,
                            @Doc("Experience levels it cost.") int levels) {}

    /** 哪座铁砧、哪件东西、合上什么。 */
    public record Combine(@Doc("The anvil's cell.") BlockPos anvil,
                          @Doc("The item to work on, minecraft:diamond_pickaxe: one you carry.") StackRef item,
                          @Doc("What goes onto it: another of the same item, an enchanted book, or the material it is "
                                  + "repaired with.") StackRef other,
                          @Doc("Also name the result.") @Omitted("keep its name") Optional<String> name,
                          @Doc("How many of other to put in: a repair with its material uses up one or more.")
                          @Omitted("one") Optional<Integer> count) {}

    @Fn("Combine an item you carry with another at an anvil: repair it, or move the other one's enchantments onto it.")
    @Example("numen.anvil.combine({x = 120, y = 64, z = -35}, \"minecraft:diamond_pickaxe\", \"minecraft:enchanted_book\")")
    @Example("numen.anvil.combine({x = 120, y = 64, z = -35}, \"minecraft:iron_pickaxe\", \"minecraft:iron_ingot\", "
            + "{count = 2, name = \"Digger\"})")
    @Note("Walks up to the anvil when it is out of reach, puts the two in, takes the result and anything left of "
            + "other back. The item and other taken are the first of each in your inventory; pass an entry of "
            + "numen.inv.items() for either to name which one (the damaged pickaxe, the book with the enchantment "
            + "you want).")
    @Note("Returns the item as it is now and the experience levels it cost. When the two do not combine, or you "
            + "have fewer levels than it costs, it fails saying so, and what was put in comes back to you.")
    @SeeAlso("numen.anvil.rename")
    public static Pending<AnvilWork> combine(ServerCall call, Combine args) {
        Integer count = Stations.count("count", args.count());
        return work(call, args.anvil(), args.item().in(call.her().getInventory()),
                args.other().in(call.her().getInventory()), count == null ? 1 : count, args.name().orElse(null));
    }

    /** 哪座铁砧、哪件东西、叫什么。 */
    public record Rename(@Doc("The anvil's cell.") BlockPos anvil,
                         @Doc("The item to name, minecraft:iron_sword: one you carry.") StackRef item,
                         @Doc("Its new name, up to " + AnvilMenu.MAX_NAME_LENGTH + " characters.") String name) {}

    @Fn("Give an item you carry a name at an anvil.")
    @Example("numen.anvil.rename({x = 120, y = 64, z = -35}, \"minecraft:iron_sword\", \"Excalibur\")")
    @Note("Walks up to the anvil when it is out of reach, puts the item in, names it and takes it back.")
    @Note("Returns the item as it is now and the experience levels it cost. It fails, and the item comes back to "
            + "you, when you have fewer levels than it costs or the item already has that name.")
    @SeeAlso("numen.anvil.combine")
    public static Pending<AnvilWork> rename(ServerCall call, Rename args) {
        return work(call, args.anvil(), args.item().in(call.her().getInventory()), null, 0, args.name());
    }

    /**
     * 两件事同一套步骤:放进去(另一件可以没有),改名(可以不改),读结果与要花的经验,取结果,另一件有剩的拿回来。
     *
     * @param other 合上去的那一件;只改名是 null
     * @param name  要起的名字;不改名是 null
     */
    private static Pending<AnvilWork> work(ServerCall call, BlockPos anvil, Stacks.Which item, Stacks.Which other,
                                           int count, String name) {
        if (name != null && (name.isBlank() || name.length() > AnvilMenu.MAX_NAME_LENGTH)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "a name is 1 to " + AnvilMenu.MAX_NAME_LENGTH + " characters",
                    null);
        }
        AtomicReference<AnvilWork> made = new AtomicReference<>();
        List<Step> steps = new ArrayList<>();
        steps.add(Move.put(item, 1, ITEM, "the item to work on"));
        if (other != null) {
            steps.add(Move.put(other, count, OTHER, "what goes onto it"));
        }
        steps.add(new Deed((menu, her) -> {
            if (name != null) {
                her.packets().renameItem(name);
            }
            made.set(priced((AnvilMenu) menu, her, name, other != null));
            return name == null ? null : "named it \"" + name + "\"";
        }));
        steps.add(Move.take(null, null, RESULT, "the result", false));
        if (other != null && count > 1) {
            // 修理用掉几件材料由原版定,多放的留在格里:拿回来
            steps.add(Move.take(null, null, OTHER, null, true));
        }
        return Stations.at(call, anvil, AnvilMenu.class, KIND, steps, made::get);
    }

    /** 此刻的结果与它要花的经验;没有结果、或她的经验不够取,带着读到的事实收场。 */
    private static AnvilWork priced(AnvilMenu menu, NumenPlayer her, String name, boolean combining) {
        ItemStack item = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
        ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
        int cost = menu.getCost();
        if (result.isEmpty()) {
            // 原版的铁砧界面在要花 40 级以上时写"过于昂贵"、不给结果
            String why = cost >= 40 ? "the work would cost " + cost + " experience levels, which it refuses as too expensive"
                    : combining ? StackLabel.brief(item) + " and "
                            + StackLabel.brief(menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem()) + " do not combine"
                    : name != null && item.getHoverName().getString().equals(name) ? "the item is already named \""
                            + name + "\""
                    : "it makes nothing of " + StackLabel.brief(item);
            throw new ApiError(ErrorKind.FAILED, "the anvil shows no result: " + why, null);
        }
        boolean free = her.hasInfiniteMaterials();
        if (!free && her.experienceLevel < cost) {
            throw new ApiError(ErrorKind.FAILED, "the anvil asks " + cost + " experience levels for "
                    + StackLabel.brief(result) + "; you have " + her.experienceLevel, null);
        }
        return new AnvilWork(Stations.product(result), free ? 0 : cost);
    }
}
