package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/** Adds compact server-authoritative proof to every asynchronous action result. */
public final class TaskEvidence {

    private TaskEvidence() { }

    static Map<String, Integer> inventoryTotals(NumenPlayer player) {
        LinkedHashMap<String, Integer> totals = new LinkedHashMap<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (stack.isEmpty()) continue;
            totals.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(), Integer::sum);
        }
        return totals;
    }

    static TaskResult decorate(NumenPlayer player, TaskRecord record, TaskResult original) {
        if (original == null) original = TaskResult.fail("no result produced");
        LinkedHashMap<String, Object> data = new LinkedHashMap<>();
        if (original.data() != null) data.putAll(original.data());

        LinkedHashMap<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("verified_at_game_time", player.level.getGameTime());
        evidence.put("dimension", player.level.dimension().location().toString());
        evidence.put("position", Map.of(
                "x", round(player.getX()), "y", round(player.getY()), "z", round(player.getZ())));
        evidence.put("health", Map.of(
                "hp", round(player.getHealth()), "max_hp", round(player.getMaxHealth()),
                "hunger", player.getFoodData().getFoodLevel()));
        evidence.put("inventory", inventoryEvidence(record.getInventoryBefore(), inventoryTotals(player)));
        evidence.put("task", Map.of(
                "tool", record.getToolName(),
                "state", record.getState().name().toLowerCase(),
                "recovery_attempts", record.getRecoveryAttempts(),
                "restored_after_restart", record.wasRestored()));
        Map<String, Object> target = targetEvidence(player, record);
        if (!target.isEmpty()) evidence.put("target", target);
        data.put("evidence", evidence);

        if (!original.success() && !original.interrupted()) {
            Recovery recovery = recovery(record);
            data.put("recovery", Map.of(
                    "retry_safe", recovery.safe,
                    "retry_budget", recovery.budget,
                    "strategy", recovery.strategy));
        }
        return new TaskResult(original.success(), original.message(), original.timedOut(),
                original.interrupted(), data);
    }

    /** Adds final server state to synchronous action results (fill/command/transfer/query adapters). */
    public static String decorateImmediate(NumenPlayer player, String tool, String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("success")) return json;
            JsonObject data = root.has("data") && root.get("data").isJsonObject()
                    ? root.getAsJsonObject("data") : new JsonObject();
            if (!data.has("evidence")) {
                JsonObject evidence = new JsonObject();
                evidence.addProperty("verified_at_game_time", player.level.getGameTime());
                evidence.addProperty("dimension", player.level.dimension().location().toString());
                JsonObject position = new JsonObject();
                position.addProperty("x", round(player.getX()));
                position.addProperty("y", round(player.getY()));
                position.addProperty("z", round(player.getZ()));
                evidence.add("position", position);
                evidence.addProperty("tool", tool);
                evidence.add("inventory_totals", new com.google.gson.Gson().toJsonTree(inventoryTotals(player)));
                data.add("evidence", evidence);
                root.add("data", data);
            }
            return root.toString();
        } catch (RuntimeException ignored) {
            return json;
        }
    }

    private static Map<String, Object> inventoryEvidence(Map<String, Integer> before, Map<String, Integer> after) {
        ArrayList<Map<String, Object>> delta = new ArrayList<>();
        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(before.keySet());
        ids.addAll(after.keySet());
        for (String id : ids) {
            int change = after.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (change != 0) delta.add(Map.of("item", id, "change", change, "final_count", after.getOrDefault(id, 0)));
        }
        int used = 0;
        for (Integer count : after.values()) if (count != null && count > 0) used++;
        return Map.of("changed_items", delta, "distinct_item_types", used);
    }

    private static Map<String, Object> targetEvidence(NumenPlayer player, TaskRecord record) {
        try {
            JsonObject args = JsonParser.parseString(record.getArgumentsJson()).getAsJsonObject();
            if (!(args.has("x") && args.has("y") && args.has("z"))) return Map.of();
            BlockPos pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            if (!player.level.hasChunkAt(pos)) return Map.of(
                    "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(), "loaded", false);
            var state = player.level.getBlockState(pos);
            return Map.of(
                    "x", pos.getX(), "y", pos.getY(), "z", pos.getZ(),
                    "loaded", true,
                    "block", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                    "state", state.toString());
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private static Recovery recovery(TaskRecord record) {
        String code = record.getFailureCode() == null ? "unknown" : record.getFailureCode().code();
        return switch (code) {
            case "stuck" -> new Recovery(true, 2, "rescan nearby terrain, choose a reachable intermediate waypoint, then retry");
            case "timeout" -> new Recovery(true, 1, "recheck current state; continue only the remaining work with a smaller batch or nearer target");
            case "invalid_target", "entity_unavailable" -> new Recovery(true, 1, "inspect the target again and skip work already satisfied by world state");
            case "missing_item", "missing_tool" -> new Recovery(false, 0, "inspect inventory and obtain the missing resource before retrying");
            default -> new Recovery(false, 0, "inspect the evidence and failure_code before selecting a different safe action");
        };
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private record Recovery(boolean safe, int budget, String strategy) { }
}
