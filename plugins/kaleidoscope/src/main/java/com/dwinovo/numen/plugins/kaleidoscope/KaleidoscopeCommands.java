package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.NumenCli;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
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
 * {@code numen kaleidoscope}:查一口锅能做什么、看一格锅现在怎样、在一格锅上做一道菜。
 *
 * <p>三个动作都在服务端:锅的状态机、配方表、品质评估都住在那边。都不提升成快捷工具——联动的动作是长尾,
 * 走 {@code numen} 这一个入口就够了。
 */
final class KaleidoscopeCommands {

    static final String GROUP = "kaleidoscope";
    static final String RECIPES = "recipes";
    static final String INSPECT = "inspect";
    static final String COOK = "cook";

    /** 一口锅两百多条配方,一次全发出去就是把这一轮的上下文塞满。 */
    private static final int MAX_ROWS = 30;

    private static final Param<String> COOKWARE = Param.required("cookware", ArgType.word(), "Which cookware.")
            .values(Arrays.stream(Cookware.values()).map(Cookware::id).collect(Collectors.joining(" or ")));
    private static final Param<Boolean> HAVE_ONLY = Param.optional("have_only", ArgType.bool(),
            "true = only dishes you can cook from your inventory right now.")
            .whenOmitted("list dishes whether you have the ingredients or not");
    private static final Param<String> NAME = Param.optional("name", ArgType.string(),
            "Only recipes whose recipe or dish id contains this, e.g. rice.")
            .whenOmitted("match every recipe");
    private static final Param<Integer> X = Param.required("x", ArgType.integer(), "Block X of the cookware.");
    private static final Param<Integer> Y = Param.required("y", ArgType.integer(), "Block Y of the cookware.");
    private static final Param<Integer> Z = Param.required("z", ArgType.integer(), "Block Z of the cookware.");
    private static final Param<ResourceLocation> RECIPE = Param.required("recipe", ArgType.id(), "The dish to cook.")
            .values("a recipe id exactly as " + line(RECIPES) + " prints it");

    private KaleidoscopeCommands() {}

    /** 回执与事件里提到别的动作时写的那一行命令。 */
    static String line(String action) {
        return NumenCli.ROOT + " " + GROUP + " " + action;
    }

    static void install(NumenApi numen) {
        numen.registerCommands(GROUP, "Kaleidoscope Cookery pots and stockpots: recipes, reading one, cooking a dish.",
                KaleidoscopeCommands::actions);
    }

    private static void actions(CommandGroup kc) {
        kc.server(RECIPES, "What the cookware can cook: recipe id, ingredients with portions, carrier, kitchenware, "
                        + "time.",
                KaleidoscopeCommands::recipes, COOKWARE, HAVE_ONLY, NAME)
                .example(line(RECIPES) + " pot --have_only true")
                .example(line(RECIPES) + " stockpot --name rice")
                .note("Read-only. Shows at most " + MAX_ROWS + "; narrow it with --name or --have_only.")
                .note("Flex recipes list THIS world's golden ratio; every save has its own.")
                .seeAlso(line(INSPECT), line(COOK));
        kc.server(INSPECT, "Read one pot or stockpot from any distance: stage, contents, heat, ticks left, what it "
                        + "waits for.",
                KaleidoscopeCommands::inspect, X, Y, Z)
                .example(line(INSPECT) + " 120 64 -35")
                .note("Read-only. Check a cookware is free before you cook on it.")
                .seeAlso(line(COOK));
        kc.server(COOK, "Cook one dish start to finish on the cookware at x y z.",
                KaleidoscopeCommands::cook, X, Y, Z, RECIPE)
                .example(line(COOK) + " 120 64 -35 kaleidoscope_cookery:flex_pot/braised_beef")
                .note("Background work: returns at once, and the result arrives as a task_finished event. "
                        + "One dish at a time.")
                .note("It does not walk: stand within reach of the cookware first.")
                .note("Uses the ingredients, oil and container from YOUR inventory. Asks your owner first when "
                        + "their rules say so, for using the cookware and for taking the dish.")
                .seeAlso(line(RECIPES), line(INSPECT), "numen task stop");
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

        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        for (Dish dish : Dish.menu(level, cookware)) {
            if (needle != null
                    && !dish.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                    && !Dish.idOf(dish.result().getItem()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (haveOnly && dish.missingFor(src.companion(), level) != null) {
                continue;
            }
            matched++;
            if (rows.size() < MAX_ROWS) {
                rows.add(dish.row(level));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("cookware", cookware.id());
        data.put("matched", matched);
        data.put("shown", rows.size());
        data.put("recipes", rows);
        data.put("quality_notes", List.of(
                "Quality grading only exists for flex recipes, and it compares the RATIO of the portions,"
                        + " not the total: the pot always hands the evaluator a 9-slot list, so the quantity"
                        + " factor is always 1 and 2:1 grades exactly the same as 4:2.",
                "A flex recipe with a SINGLE ingredient is always graded SUPERB whatever the amount, because"
                        + " the same 9-slot list makes its count check pass every time — one portion is enough."));
        src.reply(TaskResult.ok(matched > rows.size()
                ? "showing " + rows.size() + " of " + matched + " — narrow it with --name or --have_only"
                : matched + " recipe(s)", data).toJson());
    }

    private static void inspect(ServerSource src, CommandArgs args) {
        BlockPos pos = new BlockPos(args.get(X), args.get(Y), args.get(Z));
        Cooker cooker = Cooker.at(src.companion().serverLevel(), pos);
        if (cooker == null) {
            src.reply(TaskResult.fail("nothing at " + Cooker.where(pos) + " is a pot or a stockpot"
                    + " (steamers, chopping boards, millstones and spits are not wired up yet)").toJson());
            return;
        }
        src.reply(TaskResult.ok(cooker.kind().id() + " at " + Cooker.where(pos), cooker.report()).toJson());
    }

    /** 派活式:受理即回执,收尾走 {@code task_finished}——一锅汤能炖好几分钟,回合挂着等它等于把对话冻住。 */
    private static void cook(ServerSource src, CommandArgs args) {
        TaskDispatch.setTask(src, new CookRecord(src, new BlockPos(args.get(X), args.get(Y), args.get(Z)),
                args.get(RECIPE)));
    }
}
