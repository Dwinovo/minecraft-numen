package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.tool.Schema;
import com.dwinovo.numen.core.tool.ServerNumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

import java.util.Map;
import java.util.function.Consumer;

public final class CreativeGiveTool extends ServerNumenTool {

    private static final Gson GSON = new Gson();
    private static final int MAX_COUNT = 64;

    private record Args(String item_id, int count) {}

    @Override
    public String name() {
        return "creative_give";
    }

    @Override
    public String description() {
        return "Give yourself any item from the creative inventory. ONLY works when "
                + "you are in creative mode. Use the exact Minecraft item id "
                + "(e.g. minecraft:diamond, minecraft:oak_log). Max " + MAX_COUNT + " per call.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Minecraft item id, e.g. minecraft:diamond_block")
                .optionalInteger("count", "How many (1-" + MAX_COUNT + "), default 1", 1, MAX_COUNT)
                .build();
    }

    @Override
    public void runOnServer(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);

        if (companion.gameMode.getGameModeForPlayer() != GameType.CREATIVE) {
            reply.accept(TaskResult.fail("creative_give only works in creative mode. "
                    + "Ask your owner to run: /numen player " + companion.getName().getString()
                    + " gamemode creative").toJson());
            return;
        }

        ResourceLocation rl = ResourceLocation.tryParse(a.item_id());
        if (rl == null) {
            reply.accept(TaskResult.fail("Invalid item id: " + a.item_id()).toJson());
            return;
        }

        Item item = BuiltInRegistries.ITEM.get(rl);
        if (item == net.minecraft.world.item.Items.AIR && !"minecraft:air".equals(a.item_id())) {
            reply.accept(TaskResult.fail("Unknown item: " + a.item_id() + ". Use full id like minecraft:diamond").toJson());
            return;
        }

        int count = Math.max(1, Math.min(MAX_COUNT, a.count() > 0 ? a.count() : 1));
        ItemStack stack = new ItemStack(item, count);
        companion.getInventory().add(stack);
        companion.inventoryMenu.broadcastChanges();

        reply.accept(TaskResult.ok("Gave " + count + "x " + a.item_id() + " to yourself.").toJson());
    }
}
