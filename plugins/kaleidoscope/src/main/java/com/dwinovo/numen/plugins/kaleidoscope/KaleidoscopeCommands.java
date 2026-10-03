package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@code kaleidoscope}:查一口锅能做什么、看一格锅现在怎样、在一格锅上做一道菜。
 *
 * <p>三个动作都在服务端:锅的状态机、配方表、品质评估都住在那边。每个动作就是脚本里的一个函数
 * ({@code kaleidoscope.cook(...)}),和别的动作同一个入口。{@code cook} 不走动、不找远处的锅:不点名就是她手够得着的那一口,
 * 走过去是脚本的事({@code scan.blocks} 找到锅、{@code move.goto_(…, {arrive = "use"})} 走到够得着)。
 */
final class KaleidoscopeCommands {

    static final String GROUP = "kaleidoscope";
    static final String RECIPES = "recipes";
    static final String INSPECT = "inspect";
    static final String COOK = "cook";

    private static final Gson GSON = new Gson();

    private static final Param<String> COOKWARE = Param.required("cookware", ArgType.word(), "Which cookware.")
            .values(Arrays.stream(Cookware.values()).map(Cookware::id).collect(Collectors.joining(" or ")));
    private static final Param<Boolean> HAVE_ONLY = Param.optional("have_only", ArgType.bool(),
            "Only dishes you can cook from your inventory right now.")
            .whenOmitted("list dishes whether you have the ingredients or not");
    private static final Param<String> NAME = Param.optional("name", ArgType.string(),
            "Only recipes whose recipe or dish id contains this, e.g. rice.")
            .whenOmitted("match every recipe");
    /** 找手边的锅只翻她眼睛周围这么远:交互距离再多一格。 */
    private static final int REACH_SPAN = 6;

    private static final Param<BlockPos> COOKER = Param.required("cell", ArgType.cell(), "The cookware's cell.");
    private static final Param<BlockPos> COOK_AT = Param.optional("at", ArgType.cell(), "The cookware's cell.")
            .whenOmitted("cook on the pot or stockpot within your reach (the nearest one)");
    private static final Param<ResourceLocation> RECIPE = Param.required("recipe", ArgType.id(), "The dish to cook.")
            .values("a recipe id exactly as " + line(RECIPES) + " prints it");

    private KaleidoscopeCommands() {}

    /** 回执与事件里提到别的动作时写它的函数:{@code kaleidoscope.recipes}。 */
    static String line(String action) {
        return GROUP + "." + action;
    }

    /** 相关动作里点名一个动作:{@code kaleidoscope cook}。 */
    private static String path(String action) {
        return GROUP + " " + action;
    }

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Kaleidoscope Cookery pots and stockpots: recipes, reading one, cooking a dish.",
                KaleidoscopeCommands::actions);
    }

    private static void actions(CommandGroup kc) {
        kc.server(RECIPES, "What the cookware can cook: recipe id, ingredients with portions, carrier, kitchenware, "
                        + "time.",
                KaleidoscopeCommands::recipes, COOKWARE, HAVE_ONLY, NAME, Listing.PAGE)
                .example(line(RECIPES) + "(\"pot\", {have_only = true})")
                .example(line(RECIPES) + "(\"stockpot\", {name = \"rice\"})")
                .note("Read-only. One recipe per line; a pot knows a few hundred, so the list comes in pages — "
                        + "narrow it with name or have_only instead of paging through all of them.")
                .note("Flex recipes list THIS world's golden ratio; every save has its own.")
                .seeAlso(path(INSPECT), path(COOK));
        kc.server(INSPECT, "Read one pot or stockpot from any distance: stage, contents, heat, ticks left, what it "
                        + "waits for.",
                KaleidoscopeCommands::inspect, COOKER)
                .example(line(INSPECT) + "({120, 64, -35})")
                .note("Read-only. Check a cookware is free before you cook on it.")
                .seeAlso(path(COOK));
        kc.server(COOK, "Cook one dish start to finish on a pot or stockpot within your reach.",
                KaleidoscopeCommands::cook, RECIPE, COOK_AT)
                .example(line(COOK) + "(\"kaleidoscope_cookery:flex_pot/braised_beef\")")
                .example(line(COOK) + "(\"kaleidoscope_cookery:flex_pot/braised_beef\", {at = {120, 64, -35}})")
                .note("Background work: the result arrives as a task_finished event. One dish at a time.")
                .note("It does not walk and does not look for a pot further away: stand within reach of the "
                        + "cookware first (`scan.blocks` finds one, `move.goto_` it with arrive = \"use\"). Out of "
                        + "reach, no pot or "
                        + "stockpot there, an unknown recipe or a cookware already in use is refused at once with the "
                        + "reason, and nothing starts.")
                .note("Uses the ingredients, oil and container from YOUR inventory. Asks your owner first when "
                        + "their rules say so, for using the cookware and for taking the dish.")
                .seeAlso(path(RECIPES), path(INSPECT), "task stop");
    }

    private static void recipes(ServerSource src, CommandArgs args) {
        Cookware cookware = Cookware.byId(args.get(COOKWARE));
        if (cookware == null) {
            src.reply(TaskResult.fail("unknown cookware '" + args.get(COOKWARE) + "' — only pot and stockpot"
                    + " are wired up (steamer, chopping board, millstone and spit are not)").toJson());
            return;
        }
        ServerLevel level = src.companion().serverLevel();
        String needle = args.get(NAME) == null ? null : args.get(NAME).toLowerCase(Locale.ROOT);
        boolean haveOnly = Boolean.TRUE.equals(args.get(HAVE_ONLY));

        List<String> rows = new ArrayList<>();
        for (Dish dish : Dish.menu(level, cookware)) {
            if (needle != null
                    && !dish.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                    && !Dish.idOf(dish.result().getItem()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (haveOnly && dish.missingFor(src.companion(), level) != null) {
                continue;
            }
            rows.add(GSON.toJson(dish.row(level)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("quality_notes", List.of(
                "Quality grading only exists for flex recipes, and it compares the RATIO of the portions,"
                        + " not the total: the pot always hands the evaluator a 9-slot list, so the quantity"
                        + " factor is always 1 and 2:1 grades exactly the same as 4:2.",
                "A flex recipe with a SINGLE ingredient is always graded SUPERB whatever the amount, because"
                        + " the same 9-slot list makes its count check pass every time — one portion is enough."));
        String head = rows.isEmpty()
                ? "No " + cookware.id() + " recipe matches."
                : rows.size() + " " + cookware.id() + " recipe(s), one per line:";
        src.reply(new Listing(head, rows, "").result(args, data).toJson());
    }

    private static void inspect(ServerSource src, CommandArgs args) {
        BlockPos pos = args.get(COOKER);
        Cooker cooker = Cooker.at(src.companion().serverLevel(), pos);
        if (cooker == null) {
            src.reply(TaskResult.fail("nothing at " + Cooker.where(pos) + " is a pot or a stockpot"
                    + " (steamers, chopping boards, millstones and spits are not wired up yet)").toJson());
            return;
        }
        src.reply(TaskResult.ok(cooker.kind().id() + " at " + Cooker.where(pos), cooker.report()).toJson());
    }

    /**
     * 派活式:受理即回执,收尾走 {@code task_finished}——一锅汤能炖好几分钟,回合挂着等它等于把对话冻住。没写 {@code at} 就是
     * 她手边够得着的那口锅(最近的);重启后重放照这一刻认下的那一格。
     */
    private static void cook(ServerSource src, CommandArgs args) {
        BlockPos at = args.get(COOK_AT) != null ? args.get(COOK_AT) : withinReach(src);
        if (at == null) {
            return;
        }
        TaskDispatch.setTask(src.replayedWith(args.with(COOK_AT, at)), new CookRecord(src, at, args.get(RECIPE)));
    }

    /** 她够得着的锅里离眼睛最近的那一口;一口都没有时回执已经写好,返回 null。 */
    private static BlockPos withinReach(ServerSource src) {
        var her = src.companion();
        ServerLevel level = her.serverLevel();
        BlockPos eye = BlockPos.containing(her.getEyePosition());
        BlockPos best = null;
        for (BlockPos pos : BlockPos.betweenClosed(eye.offset(-REACH_SPAN, -REACH_SPAN, -REACH_SPAN),
                eye.offset(REACH_SPAN, REACH_SPAN, REACH_SPAN))) {
            if (her.canInteractWithBlock(pos, 0.0) && Cooker.at(level, pos) != null
                    && (best == null || pos.distSqr(eye) < best.distSqr(eye))) {
                best = pos.immutable();
            }
        }
        if (best == null) {
            src.reply(TaskResult.fail("no pot or stockpot is within my reach. `move.goto_({x, y, z}, {arrive = \"use\"})` "
                    + "with its coordinates first (`scan.blocks` finds one), then cook again.").toJson());
        }
        return best;
    }
}
