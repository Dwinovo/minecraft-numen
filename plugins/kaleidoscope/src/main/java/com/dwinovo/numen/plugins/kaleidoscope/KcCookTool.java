package com.dwinovo.numen.plugins.kaleidoscope;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskDispatch;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 在一格锅上把一道菜做完。
 *
 * <p>派活式:受理即回执,收尾走 {@code task_finished}——一锅汤能炖好几分钟,回合挂着等它
 * 等于把对话冻住。
 */
public final class KcCookTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(int x, int y, int z, String recipe) {}

    @Override
    public String name() {
        return "kc_cook";
    }

    @Override
    public String description() {
        return "Kaleidoscope Cookery: cook one dish on the pot or stockpot at x,y,z, start to finish —"
                + " oil / soup base, ingredients, stir-frying or lid, then plating. Takes the exact recipe"
                + " id from kc_recipes (do not guess it), and uses this world's golden ratio for flex"
                + " recipes. It does NOT travel: the body must ALREADY be within working reach (~4.5"
                + " blocks) of the cookware, otherwise it fails and tells you to goto first. It fails"
                + " honestly and says which step it is stuck on when an ingredient, the oil, the carrier,"
                + " the shovel, the lid or the heat source is missing, or when the cookware is busy with"
                + " somebody else's dish.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .integer("x", "Block X of the cookware.")
                .integer("y", "Block Y of the cookware.")
                .integer("z", "Block Z of the cookware.")
                .string("recipe", "Namespaced recipe id exactly as kc_recipes printed it,"
                        + " e.g. kaleidoscope_cookery:flex_pot/braised_beef.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        ResourceLocation recipe = ResourceLocation.tryParse(a.recipe());
        if (recipe == null) {
            reply.accept(TaskResult.fail("'" + a.recipe() + "' is not a valid recipe id —"
                    + " copy it from kc_recipes").toJson());
            return;
        }
        TaskDispatch.setTask(companion,
                new CookRecord(toolCallId, companion.level().getGameTime(),
                        new BlockPos(a.x(), a.y(), a.z()), recipe),
                args, reply);
    }
}
