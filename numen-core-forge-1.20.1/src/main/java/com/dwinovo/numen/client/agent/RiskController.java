package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.tool.ToolInvocation;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.BlockPos;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One-time owner confirmation and reservation enforcement before a tool reaches the server. */
final class RiskController {

    private static final long TOKEN_TTL_MILLIS = 5 * 60 * 1000L;
    private static final Set<String> ALWAYS_CONFIRM = Set.of(
            "drop_items", "run_command", "fill", "creative_give");

    private final AutonomyMemory memory;
    private final Map<String, Approval> approvals = new HashMap<>();
    private boolean ownerAuthorizedDrop;

    RiskController(AutonomyMemory memory) {
        this.memory = memory;
    }

    void noteOwnerPrompt(String prompt) {
        String value = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        boolean negated = value.contains("不要丢") || value.contains("别丢") || value.contains("don't drop")
                || value.contains("do not drop");
        ownerAuthorizedDrop = !negated && (value.contains("丢") || value.contains("扔")
                || value.contains("给我") || value.contains("交给我") || value.contains("drop")
                || value.contains("toss") || value.contains("give me"));
    }

    String preflight(ToolInvocation invocation, AbstractClientPlayer entity) {
        purgeExpired();
        JsonObject args = parse(invocation.argsJson());
        String reservationBlock = reservationBlock(invocation.name(), args, entity);
        if (reservationBlock != null) {
            return TaskResult.fail(reservationBlock, "reserved_resource", Map.of(
                    "risk", "blocked", "owner_confirmation_allowed", false)).toJson();
        }

        if ("drop_items".equals(invocation.name()) && ownerAuthorizedDrop) {
            return null;
        }
        String reason = riskReason(invocation.name(), args, entity);
        if (reason == null) return null;
        String fingerprint = fingerprint(invocation.name(), invocation.argsJson());
        Approval approval = approvals.get(fingerprint);
        if (approval != null && approval.approved && approval.expiresAt >= System.currentTimeMillis()) {
            approvals.remove(fingerprint);
            return null;
        }
        String token = approval == null ? token(fingerprint) : approval.token;
        approvals.put(fingerprint, new Approval(token, reason, System.currentTimeMillis() + TOKEN_TTL_MILLIS, false));
        return TaskResult.fail("需要主人确认：" + reason + "。请主人在对话中输入：确认 " + token,
                "confirmation_required", Map.of(
                        "risk", "high", "confirmation_token", token,
                        "expires_in_seconds", TOKEN_TTL_MILLIS / 1000L,
                        "tool", invocation.name())).toJson();
    }

    String approve(String token) {
        purgeExpired();
        String normalized = token == null ? "" : token.trim().toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Approval> entry : approvals.entrySet()) {
            Approval value = entry.getValue();
            if (value.token.equals(normalized)) {
                approvals.put(entry.getKey(), new Approval(value.token, value.reason, value.expiresAt, true));
                return "已批准一次危险操作（" + value.reason + "），有效期五分钟；AI 必须以完全相同的参数重新调用。";
            }
        }
        return "确认码无效或已过期，危险操作未批准。";
    }

    private String reservationBlock(String tool, JsonObject args, AbstractClientPlayer entity) {
        List<AutonomyMemory.Reservation> reservations = memory.reservations();
        if (reservations.isEmpty()) return null;
        String purpose = args.has("reservation_purpose") ? args.get("reservation_purpose").getAsString() : "";
        // Composite crafting/building and GUI transfers require server recipe/slot knowledge.
        // ReservationGuard performs their authoritative material-level check.
        if ("craft_items".equals(tool) || "build_blueprint".equals(tool) || "transfer".equals(tool)) return null;
        String item = itemArgument(tool, args);
        if (item == null) return null;
        AutonomyMemory.Reservation reservation = reservations.stream()
                .filter(value -> value.item().equalsIgnoreCase(item)).findFirst().orElse(null);
        if (reservation == null || samePurpose(reservation.purpose(), purpose)) return null;
        int reserved = reservation.count();
        int carried = 0;
        if (entity != null) {
            for (var stack : entity.getInventory().items) {
                if (!stack.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(stack.getItem()).toString().equals(item)) carried += stack.getCount();
            }
        }
        int requested = args.has("count") ? args.get("count").getAsInt() : 1;
        if (carried - requested < reserved) {
            return "拒绝丢弃：" + item + " 已为目标预留 " + reserved + " 个，当前携带 " + carried + " 个";
        }
        return null;
    }

    private static String itemArgument(String tool, JsonObject args) {
        String key = switch (tool) {
            case "drop_items", "eat_item" -> "item_id";
            case "place_block" -> args.has("block_id") ? "block_id" : "block";
            default -> null;
        };
        return key != null && args.has(key) ? args.get(key).getAsString() : null;
    }

    private static boolean samePurpose(String reservedPurpose, String requestedPurpose) {
        return requestedPurpose != null && !requestedPurpose.isBlank()
                && reservedPurpose != null && reservedPurpose.equalsIgnoreCase(requestedPurpose.trim());
    }

    private static String riskReason(String tool, JsonObject args, AbstractClientPlayer entity) {
        if (ALWAYS_CONFIRM.contains(tool)) {
            return switch (tool) {
                case "drop_items" -> "丢弃物品可能导致物品被他人拾取或五分钟后消失";
                case "run_command" -> "管理员命令可能大范围修改世界、玩家或服务器规则";
                case "fill" -> "大范围填充会立即覆盖世界方块";
                case "creative_give" -> "创造模式生成物品会改变生存资源平衡";
                default -> "操作具有不可逆副作用";
            };
        }
        if ("break_block".equals(tool) && entity != null && hasCoordinates(args)) {
            BlockPos pos = new BlockPos(args.get("x").getAsInt(), args.get("y").getAsInt(), args.get("z").getAsInt());
            if (entity.level.hasChunkAt(pos) && entity.level.getBlockEntity(pos) != null) {
                return "目标是包含库存或数据的方块实体，破坏可能丢失内容";
            }
        }
        return null;
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        approvals.values().removeIf(value -> value.expiresAt < now);
    }

    private static JsonObject parse(String json) {
        try { return JsonParser.parseString(json == null || json.isBlank() ? "{}" : json).getAsJsonObject(); }
        catch (RuntimeException ignored) { return new JsonObject(); }
    }

    private static boolean hasCoordinates(JsonObject args) {
        return args.has("x") && args.has("y") && args.has("z");
    }

    private static String fingerprint(String tool, String args) {
        return tool + "\n" + (args == null ? "{}" : args.trim());
    }

    private static String token(String fingerprint) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(fingerprint.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 4).toUpperCase(Locale.ROOT);
        } catch (Exception ignored) {
            return Integer.toHexString(fingerprint.hashCode()).toUpperCase(Locale.ROOT);
        }
    }

    private record Approval(String token, String reason, long expiresAt, boolean approved) { }
}
