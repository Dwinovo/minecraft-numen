package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * {@code kaleidoscope.pot}:查一口锅能做什么、看一格锅现在怎样、在一格锅上做一步(倒油、放汤底、下料、盖盖、翻炒、装盘)。
 *
 * <p>函数都在服务端:锅的状态机、配方表、品质评估都住在那边。一个函数只做锅上的一步,不走动、不找锅;一道菜从头做到尾是 Lua 模块
 * {@code kaleidoscope.pot.cook} 把这几步排起来。翻炒那一步跟着锅炒到好,炒的那段时间是锅自己的,所以它是占身体的活;别的几步是
 * 有界短活。每一步都只站在原地动这口锅,够不着、不是锅、配方不成当场失败({@link PotActTask})。
 */
public final class KaleidoscopeApi {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "kaleidoscope";

    private KaleidoscopeApi() {}

    static void install(NumenApi numen) {
        numen.api("pot", "Kaleidoscope Cookery pots and stockpots: recipes, reading one, cooking a dish.",
                KaleidoscopeApi.class);
    }

    /** 一道菜的做法。 */
    @Doc("A recipe of a pot or stockpot.")
    public record Recipe(@Doc("What kaleidoscope.pot.fill and kaleidoscope.pot.cook take.") String recipe,
                         @Doc("What comes out, with x2 when more than one.") String dish,
                         @Doc("Each with its portions, kaleidoscope_cookery:tomato x2.") List<String> ingredients,
                         @Doc("What to take the dish out with.") Optional<String> carrier,
                         Optional<String> soupBase,
                         List<String> kitchenware,
                         int cookTicks,
                         @Doc("Pot recipes.") Optional<Integer> stirFries,
                         @Doc("Fixed or flex, and what the portions mean.") String quality) {}

    /** 一口锅能做的。 */
    @Doc("What a cookware can cook.")
    public record Recipes(@Doc("Every recipe that matches.") List<Recipe> recipes, List<String> qualityNotes) {}

    /** 查哪种锅、怎么筛。 */
    public record Menu(@Doc("Which cookware.") Cookware cookware,
                       @Doc("Only dishes you can cook from your inventory right now.")
                       @Omitted("list dishes whether you have the ingredients or not") Optional<Boolean> haveOnly,
                       @Doc("Only recipes whose recipe or dish id contains this, e.g. rice.")
                       @Omitted("match every recipe") Optional<String> name) {}

    @Fn("What the cookware can cook: recipe id, ingredients with portions, carrier, kitchenware, time.")
    @Example("kaleidoscope.pot.recipes(\"pot\", {have_only = true})")
    @Example("for _, r in ipairs(kaleidoscope.pot.recipes(\"stockpot\", {name = \"rice\"}).recipes) do "
            + "print(r.recipe) end")
    @Note("Read-only. A pot knows a few hundred recipes: narrow it with name or have_only.")
    @Note("Flex recipes list THIS world's golden ratio; every save has its own.")
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.fill"})
    public static Recipes recipes(ServerCall call, Menu args) {
        ServerLevel level = call.her().serverLevel();
        String needle = args.name().map(n -> n.toLowerCase(Locale.ROOT)).orElse(null);
        boolean haveOnly = args.haveOnly().orElse(false);
        List<Recipe> recipes = new ArrayList<>();
        for (Dish dish : Dish.menu(level, args.cookware())) {
            if (needle != null
                    && !dish.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                    && !Dish.idOf(dish.result().getItem()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (haveOnly && dish.missingFor(call.her(), level) != null) {
                continue;
            }
            recipes.add(dish.row(level));
        }
        return new Recipes(recipes, List.of(
                "Quality grading only exists for flex recipes, and it compares the RATIO of the portions, not the "
                        + "total: the pot always hands the evaluator a 9-slot list, so the quantity factor is always 1 "
                        + "and 2:1 grades exactly the same as 4:2.",
                "A flex recipe with a SINGLE ingredient is always graded SUPERB whatever the amount, because the same "
                        + "9-slot list makes its count check pass every time — one portion is enough."));
    }

    /** 一格锅此刻的样子。 */
    @Doc("One pot or stockpot as it is now.")
    public record PotState(Cookware cookware,
                           BlockPos pos,
                           @Doc("put_ingredient, cooking, finished, burnt (pot); put_soup_base, put_ingredient, cooking, "
                                   + "finished (stockpot).") String stage,
                           boolean hasHeatSource,
                           @Doc("Pot.") Optional<Boolean> hasOil,
                           @Doc("Stockpot.") Optional<Boolean> hasLid,
                           @Doc("Stockpot, once it has one.") Optional<String> soupBase,
                           List<String> inThePot,
                           Optional<String> dishBeingMade,
                           Optional<Integer> autoStartsInTicks,
                           Optional<Integer> doneInTicks,
                           Optional<Integer> burnsInTicks,
                           Optional<Integer> clearsInTicks,
                           Optional<Integer> servingsLeft,
                           @Doc("What it waits for.") List<String> needs) {}

    /** 哪一格锅。 */
    public record At(@Doc("The cookware's cell.") BlockPos cell) {}

    @Fn("Read one pot or stockpot from any distance: stage, contents, heat, ticks left, what it waits for.")
    @Example("kaleidoscope.pot.inspect({x = 120, y = 64, z = -35})")
    @Note("Read-only. Check a cookware is free before you cook on it.")
    @SeeAlso("kaleidoscope.pot.fill")
    public static PotState inspect(ServerCall call, At args) {
        Cooker cooker = Cooker.at(call.her().serverLevel(), args.cell());
        if (cooker == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "nothing at " + Cooker.where(args.cell()) + " is a pot or a "
                    + "stockpot (steamers, chopping boards, millstones and spits are not wired up yet)", null);
        }
        return cooker.report();
    }

    /** 锅上一步做完的样子。 */
    @Doc("A step on a cookware, done.")
    public record Done(@Doc("The cookware's cell.") BlockPos pos,
                       @Doc("What came out, from plate.") Optional<String> plated) {}

    /** 哪一格锅、哪道菜。 */
    public record ForDish(@Doc("The cookware's cell.") BlockPos cell,
                          @Doc("The dish: a recipe id exactly as kaleidoscope.pot.recipes prints it.")
                          ResourceLocation recipe) {}

    @Fn("Pour oil into a pot: it opens a one-minute window for the ingredients.")
    @Example("kaleidoscope.pot.oil({x = 120, y = 64, z = -35})")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Pending<Done> oil(ServerCall call, At args) {
        return call.sync(new PotActRecord(call, args.cell(), PotAct.OIL, null));
    }

    @Fn("Pour the soup base a stockpot recipe needs into the stockpot (its lid off).")
    @Example("kaleidoscope.pot.base({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:stockpot/pumpkin_soup\")")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Pending<Done> base(ServerCall call, ForDish args) {
        return call.sync(new PotActRecord(call, args.cell(), PotAct.BASE, args.recipe()));
    }

    @Fn("Put a recipe's ingredients into the pot or stockpot, as many portions of each as this world's golden ratio "
            + "says.")
    @Example("kaleidoscope.pot.fill({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:flex_pot/braised_beef\")")
    @Note("A pot takes ingredients only after oil and before it starts cooking; a stockpot after its soup base, with "
            + "the lid off.")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Pending<Done> fill(ServerCall call, ForDish args) {
        return call.sync(new PotActRecord(call, args.cell(), PotAct.FILL, args.recipe()));
    }

    @Fn("Put a stockpot's lid on, or take it off: on with ingredients inside, it starts simmering.")
    @Example("kaleidoscope.pot.lid({x = 120, y = 64, z = -35})")
    @Note("Nothing goes in or comes out while the lid is on. A stockpot never burns; kaleidoscope.pot.inspect says "
            + "done_in_ticks while it simmers.")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Pending<Done> lid(ServerCall call, At args) {
        return call.sync(new PotActRecord(call, args.cell(), PotAct.LID, null));
    }

    @Fn("Start a pot with a kitchen shovel and stir-fry it until the dish is done.")
    @Example("kaleidoscope.pot.stir({x = 120, y = 64, z = -35})")
    @Note("Background work: it returns when the dish is done, and then it has to be plated within 40 seconds or it "
            + "burns.")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Job<Done> stir(ServerCall call, At args) {
        return Job.of(new PotActRecord(call, args.cell(), PotAct.STIR, null));
    }

    @Fn("Take the dish out of the pot or stockpot with the carrier the recipe wants.")
    @Example("kaleidoscope.pot.plate({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:flex_pot/braised_beef\")")
    @Note("A dish that came out as something else (burnt, or the mix was off) still comes out and the call fails "
            + "saying what it is.")
    @Note(STAND)
    @Note(ASKS)
    @SeeAlso({"kaleidoscope.pot.inspect", "kaleidoscope.pot.recipes"})
    public static Pending<Done> plate(ServerCall call, ForDish args) {
        return call.sync(new PotActRecord(call, args.cell(), PotAct.PLATE, args.recipe()));
    }

    private static final String STAND = "It does not walk: stand within reach of the cookware first (`numen.move.to` "
            + "it with arrive = \"use\"); out of reach, no pot or stockpot there, or an unknown recipe fails at once "
            + "with the reason.";
    private static final String ASKS = "Uses what you carry. Asks your owner first when their rules say so, for using "
            + "the cookware and for taking the dish.";
}
