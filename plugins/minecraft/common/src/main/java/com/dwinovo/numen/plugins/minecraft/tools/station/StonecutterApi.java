package com.dwinovo.numen.plugins.minecraft.tools.station;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.api.sdk.Call;
import com.dwinovo.numen.api.sdk.Doc;
import com.dwinovo.numen.api.sdk.Example;
import com.dwinovo.numen.api.sdk.Fn;
import com.dwinovo.numen.api.sdk.Note;
import com.dwinovo.numen.api.sdk.Omitted;
import com.dwinovo.numen.api.sdk.Pending;
import com.dwinovo.numen.api.sdk.Positional;
import com.dwinovo.numen.api.sdk.SeeAlso;
import com.dwinovo.numen.api.sdk.ServerCall;
import com.dwinovo.numen.api.sdk.StackRef;
import com.dwinovo.numen.script.ApiError;
import com.dwinovo.numen.script.ErrorKind;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Deed;
import com.dwinovo.numen.plugins.minecraft.task.station.StationTaskRecord.Move;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.inventory.StonecutterMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.StonecutterRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * {@code minecraft.stonecutter}:切石机。把一种东西切成点名的另一种:料进输入格,按产物挑配方(按钮 = 配方在这份料的配方表里的序号),
 * 成品格里的东西按住 Shift 点它,一直切到料用完。切得出切不出看配方表,所以走过去之前就能说。
 */
public final class StonecutterApi {

    private static final String KIND = "stonecutter";
    private static final int[] INPUT = {StonecutterMenu.INPUT_SLOT};
    private static final int[] RESULT = {StonecutterMenu.RESULT_SLOT};

    private StonecutterApi() {}

    public static void install(NumenApi numen) {
        numen.api("stonecutter", "A stonecutter: cut an item into one of the things it can be cut into.",
                StonecutterApi.class);
    }

    /** 哪台切石机、切什么、切成什么、切几件。 */
    public record Cut(@Doc("The stonecutter's cell.") BlockPos stonecutter,
                      @Doc("The item to cut, minecraft:stone: one you carry.") StackRef item,
                      @Doc("What to cut it into, minecraft:stone_bricks.") Item result,
                      @Doc("How many of item to cut.") @Omitted("all you carry of it, up to a stack") @Positional
                      Optional<Integer> count) {}

    @Fn("Cut an item you carry into another at a stonecutter.")
    @Example("minecraft.stonecutter.cut({x = 120, y = 64, z = -35}, \"minecraft:stone\", \"minecraft:stone_bricks\", 16)")
    @Example("minecraft.stonecutter.cut({x = 120, y = 64, z = -35}, \"minecraft:oak_planks\", \"minecraft:oak_slab\")")
    @Note("Walks up to the stonecutter when it is out of reach, puts the items in, cuts them all and takes what was "
            + "cut; items left uncut (your inventory filled up) come back to you.")
    @Note("Returns how many of result came out. When the item cannot be cut into result it fails before walking, "
            + "listing what it can be cut into.")
    @SeeAlso("minecraft.inv.recipes")
    public static Pending<Integer> cut(ServerCall call, Cut args) {
        Integer count = Stations.count("count", args.count());
        ServerLevel level = call.her().serverLevel();
        // 这种东西切不切得出要的那一样,配方表里就有:不走到切石机跟前、不放料就能说
        recipe(level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING,
                new SingleRecipeInput(new ItemStack(args.item().item())), level), args, level.registryAccess());
        Move take = Move.take(args.result(), null, RESULT, "the result", false);
        return Stations.at(call, args.stonecutter(), StonecutterMenu.class, KIND, List.of(
                Move.put(args.item().in(call.her().getInventory(), call.her().registryAccess()), count, INPUT, "the input"),
                new Deed((menu, her) -> {
                    StonecutterMenu cutter = (StonecutterMenu) menu;
                    int n = recipe(cutter.getRecipes(), args, her.level().registryAccess());
                    her.packets().menuButton(n);
                    if (cutter.getSelectedRecipeIndex() != n
                            || cutter.getSlot(StonecutterMenu.RESULT_SLOT).getItem().isEmpty()) {
                        throw new ApiError(ErrorKind.FAILED, "the stonecutter did not start cutting "
                                + Stations.id(args.item().item()) + " into " + Stations.id(args.result()), null);
                    }
                    return null;
                }),
                take,
                Move.take(null, null, INPUT, null, true)), () -> take.moved().count());
    }

    /**
     * 这份料的配方里产物是点名那一样的那一条,是第几条(从 0 起,就是按钮号)。
     *
     * @throws ApiError 没有这样一条:说这种料能切成哪些
     */
    private static int recipe(List<RecipeHolder<StonecutterRecipe>> recipes, Cut args, RegistryAccess registries) {
        List<String> makes = new ArrayList<>();
        for (int i = 0; i < recipes.size(); i++) {
            ItemStack made = recipes.get(i).value().getResultItem(registries);
            if (made.is(args.result())) {
                return i;
            }
            makes.add(Stations.id(made.getItem()));
        }
        String item = Stations.id(args.item().item());
        String result = Stations.id(args.result());
        if (makes.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "a stonecutter cuts nothing out of " + item,
                    Call.of("minecraft.inv.recipes", result));
        }
        throw new ApiError(ErrorKind.NOT_FOUND, "a stonecutter does not cut " + item + " into " + result
                + "; it cuts it into " + String.join(", ", makes), null);
    }
}
