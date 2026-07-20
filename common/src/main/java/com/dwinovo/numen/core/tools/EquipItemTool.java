package com.dwinovo.numen.core.tools;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): equip an item (tool/weapon/armor/accessory) from the inventory. */
public final class EquipItemTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final InventoryTools impl = new InventoryTools();

    private record Args(String item_id, String slot) {}

    @Override
    public String name() {
        return "equip_item";
    }

    @Override
    public String description() {
        return "Equip an item from your OWN inventory: tool/weapon to the main hand, armor and modded "
                + "accessories (Curios/Trinkets) auto-routed to their slots. Omit slot for auto-routing; "
                + "set it only to force a hand or a specific armor piece. The previous item is stowed back.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to equip; must be in the inventory.")
                .optionalEnum("slot", "Optional target slot; omit to auto-route by item type. "
                        + "Use only to force a hand or a specific armor piece.",
                        "mainhand", "offhand", "head", "chest", "legs", "feet")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        enqueue(companion, impl.equipItem(a.item_id(), a.slot(), ctx(toolCallId, companion)), reply);
    }
}
