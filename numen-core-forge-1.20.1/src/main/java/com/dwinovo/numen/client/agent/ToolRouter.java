package com.dwinovo.numen.client.agent;

import com.dwinovo.numen.agent.tool.NumenTool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Selects a small, relevant tool surface while preserving an explicit capability-expansion path. */
public final class ToolRouter {
    private static final Set<String> ALWAYS = Set.of(
            "request_tool_group", "get_self_status", "get_owner_status", "todo_write",
            "autonomy", "remember_memory", "recall_memory", "load_skill", "pause_tasks", "resume_tasks");
    private static final Map<String, Set<String>> GROUPS = groups();
    private static final Map<UUID, Set<String>> REQUESTED = new LinkedHashMap<>();

    private ToolRouter() { }

    public static synchronized boolean request(UUID companion, String group) {
        String normalized = normalizeGroup(group);
        if (!GROUPS.containsKey(normalized) && !"all".equals(normalized)) return false;
        REQUESTED.computeIfAbsent(companion, ignored -> new LinkedHashSet<>()).add(normalized);
        return true;
    }

    public static synchronized List<NumenTool> select(UUID companion, String context,
                                                       boolean exposeAll, List<NumenTool> registered) {
        if (exposeAll) return List.copyOf(registered);
        String query = context == null ? "" : context.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> selected = new LinkedHashSet<>(ALWAYS);
        selectFromContext(query, selected);
        Set<String> requested = REQUESTED.remove(companion);
        if (requested != null) {
            if (requested.contains("all")) return List.copyOf(registered);
            for (String group : requested) selected.addAll(GROUPS.getOrDefault(group, Set.of()));
        }
        if (selected.size() == ALWAYS.size()) selected.addAll(GROUPS.get("world"));

        List<NumenTool> out = new ArrayList<>();
        for (NumenTool tool : registered) if (selected.contains(tool.name())) out.add(tool);
        return List.copyOf(out);
    }

    public static Set<String> groupNames() {
        return Set.copyOf(GROUPS.keySet());
    }

    private static void selectFromContext(String query, Set<String> selected) {
        if (containsAny(query, "build", "blueprint", "place", "break block", "建筑", "建造", "蓝图", "放置", "拆除")) {
            selected.addAll(GROUPS.get("building"));
        }
        if (containsAny(query, "craft", "recipe", "furnace", "smelt", "workbench", "合成", "配方", "熔炉", "烧炼", "工作台")) {
            selected.addAll(GROUPS.get("crafting"));
        }
        if (containsAny(query, "mine", "collect", "gather", "resource", "挖", "采集", "收集", "材料", "矿")) {
            selected.addAll(GROUPS.get("gathering"));
        }
        if (containsAny(query, "fight", "kill", "hunt", "shoot", "monster", "combat", "攻击", "战斗", "击杀", "怪物", "射击")) {
            selected.addAll(GROUPS.get("combat"));
        }
        if (containsAny(query, "inventory", "chest", "container", "transfer", "drop", "背包", "箱子", "容器", "转移", "丢")) {
            selected.addAll(GROUPS.get("inventory"));
        }
        if (containsAny(query, "move", "travel", "locate", "find", "biome", "structure", "前往", "移动", "寻找", "定位", "群系", "结构")) {
            selected.addAll(GROUPS.get("world"));
        }
        if (containsAny(query, "creative", "command", "fill", "give", "创造", "指令", "填充", "给予")) {
            selected.addAll(GROUPS.get("creative"));
        }
        if (containsAny(query, "web", "internet", "search online", "联网", "上网", "搜索资料", "查资料")) {
            selected.addAll(GROUPS.get("knowledge"));
        }
    }

    private static boolean containsAny(String text, String... needles) {
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    private static String normalizeGroup(String group) {
        return group == null ? "" : group.trim().toLowerCase(Locale.ROOT);
    }

    private static Map<String, Set<String>> groups() {
        LinkedHashMap<String, Set<String>> groups = new LinkedHashMap<>();
        groups.put("world", Set.of("move_to", "locate_structure", "locate_biome", "scan_blocks",
                "scan_nearby_entities", "inspect_block", "inspect_block_storage", "get_world_info"));
        groups.put("gathering", Set.of("collect_items", "auto_mine", "search_items", "equip_item",
                "move_to", "scan_blocks", "inspect_block"));
        groups.put("building", Set.of("save_blueprint", "plan_blueprint", "build_blueprint", "place_block",
                "break_block", "fill", "interact_at", "move_to", "scan_blocks", "inspect_block"));
        groups.put("crafting", Set.of("lookup_recipe", "plan_crafting", "craft_items", "inspect_gui",
                "transfer", "close_gui", "interact_at", "inspect_block_storage", "move_to"));
        groups.put("combat", Set.of("hunt", "shoot", "interact_entity", "eat_item", "equip_item",
                "scan_nearby_entities", "move_to"));
        groups.put("inventory", Set.of("inspect_gui", "transfer", "close_gui", "equip_item", "drop_items",
                "eat_item", "inspect_block_storage", "interact_at"));
        groups.put("creative", Set.of("creative_give", "fill", "command", "place_block", "break_block"));
        groups.put("knowledge", Set.of("web_search", "search_items", "lookup_recipe", "get_world_info"));
        return Map.copyOf(groups);
    }
}
