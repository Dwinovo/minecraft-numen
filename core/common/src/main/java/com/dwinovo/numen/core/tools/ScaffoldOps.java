package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.pathing.settings.ScaffoldMaterials;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code scaffold_materials} 的业务半边:增删改查那份垫路料清单。
 *
 * <p>四个动作都落到同一个出口——{@link ScaffoldMaterials#store}——然后回读落盘后的实际结果。
 * 回执报的永远是<b>存进去之后读回来的</b>那份,不是请求的那份:认不出的 id 会被丢掉,
 * 模型得看见这件事,否则它会以为自己加上了。
 */
public final class ScaffoldOps {

    /** 回执里最多列几种背包里没在清单上的方块——够模型挑,不至于把回执撑爆。 */
    private static final int MAX_SUGGESTIONS = 12;

    public String apply(String action, List<String> blockIds, NumenPlayer self) {
        String verb = action == null || action.isBlank() ? "read" : action.trim().toLowerCase(java.util.Locale.ROOT);
        List<String> given = ScaffoldMaterials.normalize(blockIds);

        switch (verb) {
            case "read" -> { }
            case "clear" -> ScaffoldMaterials.store(self, List.of());
            case "add" -> {
                if (given.isEmpty()) {
                    return error(self, "add needs block_ids, and none of what you gave is a real block id");
                }
                List<String> merged = new ArrayList<>(ScaffoldMaterials.effectiveIds(self));
                for (String id : given) {
                    if (!merged.contains(id)) {
                        merged.add(id);
                    }
                }
                ScaffoldMaterials.store(self, merged);
            }
            case "delete" -> {
                if (given.isEmpty()) {
                    return error(self, "delete needs block_ids, and none of what you gave is a real block id");
                }
                List<String> kept = new ArrayList<>(ScaffoldMaterials.effectiveIds(self));
                kept.removeAll(given);
                ScaffoldMaterials.store(self, kept);
            }
            case "set" -> {
                if (given.isEmpty()) {
                    return error(self, "set needs block_ids (use action=clear to go back to the default), "
                            + "and none of what you gave is a real block id");
                }
                ScaffoldMaterials.store(self, given);
            }
            default -> {
                return error(self, "unknown action '" + action + "' — use add / delete / set / clear, "
                        + "or omit it to just read");
            }
        }

        List<String> now = ScaffoldMaterials.effectiveIds(self);
        if (!verb.equals("read")) {
            announceToOwner(self, verb, now);
        }
        return report(self, now, verb);
    }

    /** 清单 + 背包里还没进清单的东西。两样一起给,模型读一次就能决定要不要 add。 */
    private String report(NumenPlayer self, List<String> materials, String verb) {
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        JsonArray list = new JsonArray();
        materials.forEach(list::add);
        root.add("materials", list);
        root.addProperty("customised", !ScaffoldMaterials.storedIds(self).isEmpty());

        JsonArray carrying = new JsonArray();
        for (Map.Entry<String, Integer> e : notListed(self, materials).entrySet()) {
            JsonObject o = new JsonObject();
            o.addProperty("block", e.getKey());
            o.addProperty("count", e.getValue());
            carrying.add(o);
        }
        root.add("carrying_not_listed", carrying);
        root.addProperty("message", verb.equals("read")
                ? "these are the blocks you are willing to spend on scaffolding"
                : "list updated — this is what pathfinding will spend from now on");
        return root.toString();
    }

    /** 背包里能当方块放下、却不在清单上的东西,按数量从多到少。 */
    private static Map<String, Integer> notListed(NumenPlayer self, List<String> materials) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        var inv = self.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty() || !(stack.getItem() instanceof net.minecraft.world.item.BlockItem)) {
                continue;
            }
            String id = ScaffoldMaterials.idOf(stack.getItem());
            if (materials.contains(id)) {
                continue;
            }
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return counts.entrySet().stream()
                .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                .limit(MAX_SUGGESTIONS)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
    }

    /**
     * 她改了什么当场报主人一句。这是一张长期空白支票——设一次之后寻路可能烧掉几百块,
     * 中间没有任何确认——所以主人有权知道她授权了消耗什么。
     */
    private static void announceToOwner(NumenPlayer self, String verb, List<String> now) {
        ServerPlayer owner = self.resolveOwnerPlayer();
        if (owner == null) {
            return;
        }
        owner.sendSystemMessage(Component.literal(
                "🧱 " + self.getName().getString() + " 的垫路料(" + verb + "):" + shortList(now)));
    }

    private static String shortList(List<String> ids) {
        if (ids.isEmpty()) {
            return "空";
        }
        List<String> paths = ids.stream()
                .map(id -> id.contains(":") ? id.substring(id.indexOf(':') + 1) : id)
                .toList();
        return paths.size() <= 6
                ? String.join("、", paths)
                : String.join("、", paths.subList(0, 6)) + " 等 " + paths.size() + " 种";
    }

    /** 失败也把当前清单带上——参数写错了不该连"现在是什么"都看不到。 */
    private String error(NumenPlayer self, String why) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("error", why);
        JsonArray list = new JsonArray();
        ScaffoldMaterials.effectiveIds(self).forEach(list::add);
        root.add("materials", list);
        return root.toString();
    }
}
