package com.dwinovo.numen.core.task;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.List;
import java.util.Map;

/** Server-authoritative enforcement for the client brain's persistent resource budget. */
public final class ReservationGuard {

    public record Reservation(String item, int count, String purpose) { }
    private static final Gson GSON = new Gson();

    private ReservationGuard() { }

    public static JsonObject validate(String tool, JsonObject args, String reservationsJson, NumenPlayer player) {
        List<Reservation> reservations = parse(reservationsJson);
        if (reservations.isEmpty()) return null;
        String purpose = string(args, "reservation_purpose");

        if ("transfer".equals(tool)) {
            JsonObject failure = validateTransfers(args, reservations, purpose, player);
            if (failure != null) return failure;
        }
        String item = switch (tool) {
            case "drop_items", "eat_item" -> string(args, "item_id");
            case "place_block" -> !string(args, "block_id").isBlank()
                    ? string(args, "block_id") : string(args, "block");
            default -> "";
        };
        if (!item.isBlank()) {
            int requested = args.has("count") ? Math.max(1, args.get("count").getAsInt()) : 1;
            JsonObject failure = protectItem(item, requested, reservations, purpose, player);
            if (failure != null) return failure;
        }
        return null;
    }

    public static List<Reservation> fromArgs(JsonObject args) {
        if (args == null || !args.has("_numen_reservations")) return List.of();
        return parse(args.get("_numen_reservations").toString());
    }

    public static int protectedCount(List<Reservation> reservations, String item, String purpose) {
        return reservations.stream().filter(value -> value.item().equalsIgnoreCase(item)
                && !samePurpose(value.purpose(), purpose)).mapToInt(Reservation::count).sum();
    }

    private static JsonObject validateTransfers(JsonObject args, List<Reservation> reservations,
                                                String purpose, NumenPlayer player) {
        if (!args.has("moves") || !args.get("moves").isJsonArray() || player.containerMenu == null) return null;
        for (var element : args.getAsJsonArray("moves")) {
            JsonObject move = element.getAsJsonObject();
            int from = move.get("from").getAsInt();
            if (from < 0 || from >= player.containerMenu.slots.size()) continue;
            var slot = player.containerMenu.slots.get(from);
            if (slot.container != player.getInventory() || slot.getItem().isEmpty()) continue;
            String item = BuiltInRegistries.ITEM.getKey(slot.getItem().getItem()).toString();
            int count = move.has("count") && !move.get("count").isJsonNull()
                    ? move.get("count").getAsInt() : slot.getItem().getCount();
            JsonObject failure = protectItem(item, Math.max(1, count), reservations, purpose, player);
            if (failure != null) return failure;
        }
        return null;
    }

    private static JsonObject protectItem(String item, int requested, List<Reservation> reservations,
                                          String purpose, NumenPlayer player) {
        int reserved = protectedCount(reservations, item, purpose);
        if (reserved <= 0) return null;
        int carried = TaskEvidence.inventoryTotals(player).getOrDefault(item, 0);
        if (carried - requested >= reserved) return null;
        return JsonParser.parseString(TaskResult.fail(
                "server blocked consumption of " + item + ": " + reserved + " reserved for another goal",
                "reserved_resource", Map.of("item", item, "carried", carried,
                        "requested", requested, "protected", reserved)).toJson()).getAsJsonObject();
    }

    private static List<Reservation> parse(String json) {
        try {
            Reservation[] values = GSON.fromJson(json == null || json.isBlank() ? "[]" : json, Reservation[].class);
            return values == null ? List.of() : List.of(values);
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private static boolean samePurpose(String expected, String actual) {
        return actual != null && !actual.isBlank() && expected != null && expected.equalsIgnoreCase(actual.trim());
    }

    private static String string(JsonObject args, String key) {
        return args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
