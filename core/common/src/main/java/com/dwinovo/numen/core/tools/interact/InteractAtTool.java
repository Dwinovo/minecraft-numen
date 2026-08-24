package com.dwinovo.numen.core.tools.interact;
import com.dwinovo.numen.core.tools.BlockActionOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): aim at a world point and press a mouse button. */
public final class InteractAtTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BlockActionOps impl = new BlockActionOps();

    private record Args(String button, Integer x, Integer y, Integer z, Integer hold_ticks, String item_id) {}

    @Override
    public String name() {
        return "interact_at";
    }

    @Override
    public String description() {
        return "Aim at a world point and press one mouse button — the full native click for BLOCKS, "
                + "FLUIDS and the AIR (moving entities use interact_entity). right = use/place/activate; "
                + "if the aimed block doesn't respond, the held item acts on its own, exactly like a real "
                + "right-click — so aiming at WATER with a bucket scoops it, with a boat places it. "
                + "left = attack/break (prefer mine for digging). The result reports what actually "
                + "changed (hands, aimed block, new entities); no change listed = the click did nothing. "
                + "It does NOT travel: you must ALREADY be within working reach (~4.5 blocks) of the aim "
                + "point — goto stops you right beside a block, which is in reach. Farther away it fails "
                + "and tells you to goto first.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("button", "right = use/activate/throw, left = attack/break.", "left", "right")
                .nullableInteger("x", "Aim X. Null (with y,z null) = use the held item straight ahead (eat/drink).")
                .nullableInteger("y", "Aim Y. Null when aiming forward.")
                .nullableInteger("z", "Aim Z. Null when aiming forward.")
                .nullableInteger("hold_ticks", "0/null = single press; >0 = hold that many ticks; -1 = hold until done/timeout.")
                .nullableString("item_id", "Optional namespaced item to equip-and-use, e.g. minecraft:bonemeal. Null = use what's in hand.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        runSync(companion, impl.interactAt(a.button(), a.x(), a.y(), a.z(), a.hold_ticks(), a.item_id(),
                ctx(toolCallId, companion)), reply);
    }
}
