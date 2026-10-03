package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.agent.script.ScriptType;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.cli.ArgType;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.CommandGroup;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.Param;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.cli.Shapes;
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
 * ({@code kaleidoscope.pot.cook(...)}),和别的动作同一个入口。{@code cook} 不走动、不找远处的锅:不点名就是她手够得着的那一口,
 * 走过去是脚本的事({@code numen.scan.blocks} 找到锅、{@code numen.move.goto_(…, {arrive = "use"})} 走到够得着)。
 */
final class KaleidoscopeCommands {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "kaleidoscope";
    static final String GROUP = "pot";
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

    /** 回执与事件里提到别的动作时写它的函数:{@code kaleidoscope.pot.recipes}。 */
    static String line(String action) {
        return NAMESPACE + "." + GROUP + "." + action;
    }

    /** 相关动作里点名一个动作:{@code pot cook}。 */
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
                .returns(ScriptType.table(
                        ScriptType.field("recipes", ScriptType.listOf(ScriptType.table(
                                ScriptType.field("recipe", ScriptType.STRING, "What " + line(COOK) + " takes."),
                                ScriptType.field("dish", ScriptType.STRING, "What comes out, with x2 when more "
                                        + "than one."),
                                ScriptType.field("ingredients", ScriptType.listOf(ScriptType.STRING),
                                        "Each with its portions, kaleidoscope_cookery:tomato x2."),
                                ScriptType.optional("carrier", ScriptType.STRING, "What to take the dish out with."),
                                ScriptType.optional("soup_base", ScriptType.STRING, null),
                                ScriptType.field("kitchenware", ScriptType.listOf(ScriptType.STRING), null),
                                ScriptType.field("cook_ticks", ScriptType.INTEGER, null),
                                ScriptType.optional("stir_fries", ScriptType.INTEGER, "Pot recipes."),
                                ScriptType.field("quality", ScriptType.STRING, "Fixed or flex, and what the "
                                        + "portions mean."))), "Every recipe that matches."),
                        ScriptType.field("quality_notes", ScriptType.listOf(ScriptType.STRING), null)))
                .example(line(RECIPES) + "(\"pot\", {have_only = true})")
                .example("for _, r in ipairs(" + line(RECIPES) + "(\"stockpot\", {name = \"rice\"}).recipes) do "
                        + "print(r.recipe) end")
                .note("Read-only. The reply lists one recipe per line; a pot knows a few hundred, so it comes in "
                        + "pages — narrow it with name or have_only instead of paging through all of them.")
                .note("Flex recipes list THIS world's golden ratio; every save has its own.")
                .seeAlso(path(INSPECT), path(COOK));
        kc.server(INSPECT, "Read one pot or stockpot from any distance: stage, contents, heat, ticks left, what it "
                        + "waits for.",
                KaleidoscopeCommands::inspect, COOKER)
                .returns(ScriptType.table(
                        ScriptType.field("cookware", ScriptType.choice(List.of("pot", "stockpot")), null),
                        ScriptType.field("pos", Shapes.POS.type(), null),
                        ScriptType.field("stage", ScriptType.STRING, "put_ingredient, cooking, finished, burnt "
                                + "(pot); put_soup_base, put_ingredient, cooking, finished (stockpot)."),
                        ScriptType.field("has_heat_source", ScriptType.BOOLEAN, null),
                        ScriptType.optional("has_oil", ScriptType.BOOLEAN, "Pot."),
                        ScriptType.optional("has_lid", ScriptType.BOOLEAN, "Stockpot."),
                        ScriptType.optional("soup_base", ScriptType.STRING, "Stockpot, once it has one."),
                        ScriptType.field("in_the_pot", ScriptType.listOf(ScriptType.STRING), null),
                        ScriptType.optional("dish_being_made", ScriptType.STRING, null),
                        ScriptType.optional("auto_starts_in_ticks", ScriptType.INTEGER, null),
                        ScriptType.optional("done_in_ticks", ScriptType.INTEGER, null),
                        ScriptType.optional("burns_in_ticks", ScriptType.INTEGER, null),
                        ScriptType.optional("clears_in_ticks", ScriptType.INTEGER, null),
                        ScriptType.optional("servings_left", ScriptType.INTEGER, null),
                        ScriptType.field("needs", ScriptType.listOf(ScriptType.STRING), "What it waits for.")))
                .example(line(INSPECT) + "({x = 120, y = 64, z = -35})")
                .note("Read-only. Check a cookware is free before you cook on it.")
                .seeAlso(path(COOK));
        kc.server(COOK, "Cook one dish start to finish on a pot or stockpot within your reach.",
                KaleidoscopeCommands::cook, RECIPE, COOK_AT)
                .returns(ScriptType.table(ScriptType.field("recipe", ScriptType.STRING, null),
                        ScriptType.field("pos", Shapes.POS.type(), "The cookware's cell."),
                        ScriptType.optional("plated", ScriptType.STRING, "What came out.")))
                .example(line(COOK) + "(\"kaleidoscope_cookery:flex_pot/braised_beef\")")
                .example(line(COOK) + "(\"kaleidoscope_cookery:flex_pot/braised_beef\", {at = {x = 120, y = 64, "
                        + "z = -35}})")
                .note("Background work: the result arrives as a task_finished event. One dish at a time.")
                .note("It does not walk and does not look for a pot further away: stand within reach of the "
                        + "cookware first (`numen.scan.blocks` finds one, `numen.move.goto_` it with arrive = \"use\"). Out of "
                        + "reach, no pot or "
                        + "stockpot there, an unknown recipe or a cookware already in use is refused at once with the "
                        + "reason, and nothing starts.")
                .note("Uses the ingredients, oil and container from YOUR inventory. Asks your owner first when "
                        + "their rules say so, for using the cookware and for taking the dish.")
                .seeAlso(path(RECIPES), path(INSPECT), "numen task stop");
    }

    private static void recipes(ServerSource src, CommandArgs args) {
        Cookware cookware = Cookware.byId(args.get(COOKWARE));
        if (cookware == null) {
            src.reply(TaskResult.fail(ErrorKind.BAD_ARGUMENT, "unknown cookware '" + args.get(COOKWARE) + "' — only "
                    + "pot and stockpot are wired up (steamer, chopping board, millstone and spit are not)", null)
                    .toJson());
            return;
        }
        ServerLevel level = src.companion().serverLevel();
        String needle = args.get(NAME) == null ? null : args.get(NAME).toLowerCase(Locale.ROOT);
        boolean haveOnly = Boolean.TRUE.equals(args.get(HAVE_ONLY));

        List<String> rows = new ArrayList<>();
        List<Map<String, Object>> recipes = new ArrayList<>();
        for (Dish dish : Dish.menu(level, cookware)) {
            if (needle != null
                    && !dish.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                    && !Dish.idOf(dish.result().getItem()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (haveOnly && dish.missingFor(src.companion(), level) != null) {
                continue;
            }
            Map<String, Object> row = dish.row(level);
            rows.add(GSON.toJson(row));
            recipes.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recipes", recipes);
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
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "nothing at " + Cooker.where(pos) + " is a pot or a "
                    + "stockpot (steamers, chopping boards, millstones and spits are not wired up yet)", null)
                    .toJson());
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
            src.reply(TaskResult.fail(ErrorKind.NOT_FOUND, "no pot or stockpot is within my reach — find one, "
                    + "numen.move.goto_ it with arrive = \"use\", then cook again",
                    "numen.scan.blocks(\"kaleidoscope_cookery:pot\", \"kaleidoscope_cookery:stockpot\")").toJson());
        }
        return best;
    }
}
