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
 * {@code kaleidoscope}:查一口锅能做什么、看一格锅现在怎样、在一格锅上做一步(倒油、放汤底、下料、盖盖、翻炒、装盘)。
 *
 * <p>动作都在服务端:锅的状态机、配方表、品质评估都住在那边。每个动作就是脚本里的一个函数({@code kaleidoscope.pot.fill(...)}),
 * 和别的动作同一个入口。一个动作只做锅上的一步,不走动、不找锅;一道菜从头做到尾是 Lua 模块 {@code kaleidoscope.pot.cook}
 * 把这几步排起来。翻炒那一步跟着锅炒到好,炒的那段时间是锅自己的。
 */
final class KaleidoscopeCommands {

    /** 这个联动在她的 API 里的名字空间。 */
    static final String NAMESPACE = "kaleidoscope";
    static final String GROUP = "pot";
    static final String RECIPES = "recipes";
    static final String INSPECT = "inspect";
    static final String OIL = PotAct.OIL.word;
    static final String BASE = PotAct.BASE.word;
    static final String FILL = PotAct.FILL.word;
    static final String LID = PotAct.LID.word;
    static final String STIR = PotAct.STIR.word;
    static final String PLATE = PotAct.PLATE.word;

    private static final Gson GSON = new Gson();

    private static final Param<String> COOKWARE = Param.required("cookware", ArgType.word(), "Which cookware.")
            .values(Arrays.stream(Cookware.values()).map(Cookware::id).collect(Collectors.joining(" or ")));
    private static final Param<Boolean> HAVE_ONLY = Param.optional("have_only", ArgType.bool(),
            "Only dishes you can cook from your inventory right now.")
            .whenOmitted("list dishes whether you have the ingredients or not");
    private static final Param<String> NAME = Param.optional("name", ArgType.string(),
            "Only recipes whose recipe or dish id contains this, e.g. rice.")
            .whenOmitted("match every recipe");
    private static final Param<BlockPos> COOKER = Param.required("cell", ArgType.cell(), "The cookware's cell.");
    private static final Param<ResourceLocation> RECIPE = Param.required("recipe", ArgType.id(), "The dish to cook.")
            .values("a recipe id exactly as " + line(RECIPES) + " prints it");

    private KaleidoscopeCommands() {}

    /** 回执与事件里提到别的动作时写它的函数:{@code kaleidoscope.pot.recipes}。 */
    static String line(String action) {
        return NAMESPACE + "." + GROUP + "." + action;
    }

    /** 相关动作里点名一个动作:{@code pot fill}。 */
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
                                ScriptType.field("recipe", ScriptType.STRING, "What " + line(FILL) + " and "
                                        + "kaleidoscope.pot.cook take."),
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
                .seeAlso(path(INSPECT), path(FILL));
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
                .seeAlso(path(FILL));
        ScriptType done = ScriptType.table(ScriptType.field("pos", Shapes.POS.type(), "The cookware's cell."),
                ScriptType.optional("plated", ScriptType.STRING, "What came out, from plate."));
        step(kc, PotAct.OIL, "Pour oil into a pot: it opens a one-minute window for the ingredients.", done)
                .example(line(OIL) + "({x = 120, y = 64, z = -35})");
        step(kc, PotAct.BASE, "Pour the soup base a stockpot recipe needs into the stockpot (its lid off).", done,
                        RECIPE)
                .example(line(BASE) + "({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:stockpot/pumpkin_soup\")");
        step(kc, PotAct.FILL, "Put a recipe's ingredients into the pot or stockpot, as many portions of each as this "
                        + "world's golden ratio says.", done, RECIPE)
                .example(line(FILL) + "({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:flex_pot/braised_beef\")")
                .note("A pot takes ingredients only after oil and before it starts cooking; a stockpot after its soup "
                        + "base, with the lid off.");
        step(kc, PotAct.LID, "Put a stockpot's lid on, or take it off: on with ingredients inside, it starts "
                        + "simmering.", done)
                .example(line(LID) + "({x = 120, y = 64, z = -35})")
                .note("Nothing goes in or comes out while the lid is on. A stockpot never burns; "
                        + line(INSPECT) + " says done_in_ticks while it simmers.");
        step(kc, PotAct.STIR, "Start a pot with a kitchen shovel and stir-fry it until the dish is done.", done)
                .example(line(STIR) + "({x = 120, y = 64, z = -35})")
                .note("Background work: it returns when the dish is done, and then it has to be plated within 40 "
                        + "seconds or it burns.");
        step(kc, PotAct.PLATE, "Take the dish out of the pot or stockpot with the carrier the recipe wants.", done,
                        RECIPE)
                .example(line(PLATE) + "({x = 120, y = 64, z = -35}, \"kaleidoscope_cookery:flex_pot/braised_beef\")")
                .note("A dish that came out as something else (burnt, or the mix was off) still comes out and the "
                        + "call fails saying what it is.");
    }

    /**
     * 锅上的一步:都只站在原地动这口锅,够不着、不是锅、配方不成当场拒绝;翻炒跟着锅走到炒好,是派下的活,别的几步是有界短活。
     */
    private static com.dwinovo.numen.cli.Action step(CommandGroup kc, PotAct act, String summary, ScriptType returns,
                                                     Param<?>... more) {
        Param<?>[] params = new Param<?>[more.length + 1];
        params[0] = COOKER;
        System.arraycopy(more, 0, params, 1, more.length);
        return kc.server(act.word, summary, (src, args) -> {
                    PotActRecord record = new PotActRecord(src, args.get(COOKER), act,
                            act.needsDish ? args.get(RECIPE) : null);
                    if (act == PotAct.STIR) {
                        TaskDispatch.setTask(src, record);
                    } else {
                        TaskDispatch.runSync(src.companion(), record, src::reply);
                    }
                }, params)
                .returns(returns)
                .note("It does not walk: stand within reach of the cookware first (`numen.move.to` it with "
                        + "arrive = \"use\"); out of reach, no pot or stockpot there, or an unknown recipe is refused "
                        + "at once with the reason.")
                .note("Uses what you carry. Asks your owner first when their rules say so, for using the cookware "
                        + "and for taking the dish.")
                .seeAlso(path(INSPECT), path(RECIPES));
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
}
