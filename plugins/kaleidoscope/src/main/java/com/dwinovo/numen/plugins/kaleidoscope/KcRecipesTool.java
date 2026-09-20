package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** 查一口锅能做哪些菜、每道要什么料和厨具,以及这个存档的黄金配比。 */
public final class KcRecipesTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 一口锅两百多条配方,一次全发出去就是把这一轮的上下文塞满。 */
    private static final int MAX_ROWS = 30;

    private record Args(String cookware, Boolean have_only, String name) {}

    @Override
    public String name() {
        return "kc_recipes";
    }

    @Override
    public String description() {
        return "Kaleidoscope Cookery: what a pot or a stockpot can cook. Each row gives the exact recipe id"
                + " kc_cook takes, the dish, the ingredients WITH the portions to use, the carrier to plate"
                + " it into, the kitchenware needed and the cooking time. For flex recipes the portions are"
                + " THIS WORLD'S golden ratio (they grade SUPERB) — the ratio is rolled per save from the"
                + " world seed, so never carry portions over from another world or from memory. Filter with"
                + " have_only to see only what can be cooked from the current inventory, and with name to"
                + " narrow a long menu.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("cookware", "Which cookware to list. Only these two are wired up so far.",
                        "pot", "stockpot")
                .optionalBool("have_only", "True = only dishes cookable from the inventory right now."
                        + " False/null = the whole menu.")
                .optionalString("name", "Optional substring of the recipe id or dish id, e.g. 'rice'."
                        + " Null = no filter.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        Cookware cookware = Cookware.byId(a.cookware());
        if (cookware == null) {
            reply.accept(TaskResult.fail("unknown cookware '" + a.cookware() + "' — only pot and stockpot"
                    + " are wired up (steamer, chopping board, millstone and spit are not)").toJson());
            return;
        }
        ServerLevel level = companion.serverLevel();
        String needle = a.name() == null ? null : a.name().toLowerCase(Locale.ROOT);
        boolean haveOnly = Boolean.TRUE.equals(a.have_only());

        List<Map<String, Object>> rows = new ArrayList<>();
        int matched = 0;
        for (Dish dish : Dish.menu(level, cookware)) {
            if (needle != null
                    && !dish.id().toString().toLowerCase(Locale.ROOT).contains(needle)
                    && !Dish.idOf(dish.result().getItem()).toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            String missing = haveOnly ? dish.missingFor(companion, level) : null;
            if (haveOnly && missing != null) {
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
        reply.accept(TaskResult.ok(matched > rows.size()
                ? "showing " + rows.size() + " of " + matched + " — narrow it with name or have_only"
                : matched + " recipe(s)", data).toJson());
    }
}
