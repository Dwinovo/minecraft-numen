package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.task.BuildTaskRecord;
import com.dwinovo.numen.core.task.PlayerInv;
import com.dwinovo.numen.core.task.WorkProfile;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * 读一张图纸:尺寸、用料、按层分布——不动世界一格。
 *
 * <p>此前这些只能作为<b>失败的副产品</b>出现:得先选好位置、发起施工、被拒绝,
 * 才知道要多少料。实测就是这个样子——盖屋顶、缺四十一块楼梯、跑去找工作台、
 * 找不到、再试、再缺,全程没有任何一步能提前回答"这栋房子要多少料"。
 *
 * <p>而生存模式的价值恰恰在那条链上:她设计 → 报料 → 一起去采 → 施工。报料是
 * 第二环,不该靠撞墙触发。
 */
public final class BlueprintReadTool implements NumenTool {

    private static final Gson GSON = new Gson();
    /** 一句话概览里点名几种,其余只报个数——完整清单在 data 里,那才是拿去采集的。 */
    private static final int NAMED_IN_HEADLINE = 5;
    /** 按层分布最多报几层,再多就分桶。 */
    private static final int MAX_LAYERS = 24;

    private record Args(String file, Integer x, Integer y, Integer z, Integer rotation) {}

    @Override
    public String name() {
        return "blueprint_read";
    }

    @Override
    public String description() {
        return "Read a blueprint without touching the world: overall size, cell count, the materials it "
                + "costs (biggest items first), and how its cells are spread across height — the layer "
                + "profile is what lets you reason about storeys, e.g. where a second floor or a roof "
                + "starts. Call this BEFORE proposing a build so you can tell the player what it will "
                + "take; in survival that shopping list is the whole point, since a large structure needs "
                + "several gathering trips. Pass the optional anchor x,y,z (and rotation) as well and you "
                + "also get what is ALREADY standing there, what is still missing, and what she is short "
                + "of right now — use that to resume a part-built structure or to check a site before "
                + "committing to it. Instant, read-only; use the blueprint tool to actually build.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("file", Map.of("type", "string",
                "description", "Blueprint name from `blueprint action=list`, without extension."));
        props.put("x", Map.of("type", "integer",
                "description", "Optional anchor minimum X — give all three to also get site progress."));
        props.put("y", Map.of("type", "integer", "description", "Optional anchor bottom Y."));
        props.put("z", Map.of("type", "integer", "description", "Optional anchor minimum Z."));
        props.put("rotation", Map.of("type", "integer", "enum", List.of(0, 90, 180, 270),
                "description", "Optional clockwise rotation, matching the build you plan."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("file"));
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a.file() == null || a.file().isBlank()) {
            throw new IllegalArgumentException("file is required; use `blueprint action=list` first");
        }
        boolean anchored = a.x() != null && a.y() != null && a.z() != null;
        BlockPos anchor = anchored ? new BlockPos(a.x(), a.y(), a.z()) : BlockPos.ZERO;
        int quarters = a.rotation() == null ? 0 : Math.floorMod(a.rotation(), 360) / 90;
        ServerLevel level = (ServerLevel) companion.level();
        BlueprintStore.Loaded loaded = BlueprintStore.load(level, a.file(), anchor, quarters);

        Map<Item, Integer> cost = new LinkedHashMap<>();
        Map<Integer, Integer> byLayer = new TreeMap<>();
        int placed = 0;
        int clears = 0;
        Map<Item, Integer> remaining = new LinkedHashMap<>();
        int baseY = loaded.targets().stream().mapToInt(t -> t.pos().getY()).min().orElse(0);
        // 按组件全等收料的那些格(旗帜的花纹)与摆设身上带的东西,要单独点名:报价
        // 说一句"white_banner x3"而实际要的是三面绣好花纹的旗,玩家按报价备齐了照样
        // 一格都放不下去。判据严到哪里,报价就得说到哪里——这是同一个口径问题。
        Map<String, Integer> extra = new LinkedHashMap<>();
        Map<String, Integer> exact = new LinkedHashMap<>();
        for (var list : loaded.cellNeeds().values()) {
            for (var need : list) {
                (need.exact() ? exact : extra)
                        .merge(need.stack().getHoverName().getString(), 1, Integer::sum);
            }
        }
        for (var spawn : loaded.entities()) {
            for (var stack : spawn.payload(level.registryAccess())) {
                exact.merge(stack.getHoverName().getString(), 1, Integer::sum);
            }
        }
        for (BuildTaskRecord.Target t : loaded.targets()) {
            byLayer.merge(t.pos().getY() - baseY, 1, Integer::sum);
            if (!t.costsMaterial()) {
                clears++;
                continue;
            }
            // 有料单的格不进普通清单,否则同一面旗帜/同一个花盆会被索要两次
            if (loaded.cellNeeds().containsKey(t.pos().asLong())) {
                continue;
            }
            // 件数问 Target 要,不在这里另数一遍:清单与实扣一旦不同源,玩家
            // 按清单备齐了照样建到一半停下(双层砖一格两件就是这么漏掉的)
            cost.merge(t.item(), t.materialCount(), Integer::sum);
            if (anchored) {
                // 只读已加载区块:报价要扫全图纸,用 level.getBlockState 会把图纸覆盖的
                // 所有区块现场同步生成一遍——远处锚点报一次价就是一次可见卡顿
                if (t.matches(com.dwinovo.numen.core.pathing.cache.LoadedOnlyView.of(level)
                        .getBlockState(t.pos()))) {
                    placed++;
                } else {
                    remaining.merge(t.item(), t.materialCount(), Integer::sum);
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        var size = loaded.size();
        data.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
        data.put("cells", loaded.targets().size());
        data.put("cells_costing_materials", sum(cost));
        if (clears > 0) {
            data.put("cells_that_only_clear", clears);
        }
        if (loaded.dropped() > 0) {
            // 掉格要在<b>报价这一步</b>就说清:模型正是在这里决定要不要建、缺什么料。
            // 不说的话它拿到的格数就是"全部",而设计已经缺了一块,谁都不知道。
            data.put("cells_dropped", loaded.dropped()
                    + " (liquids, or blocks with no item to pay with — she will not build these)");
        }
        data.put("materials", summarize(cost));
        if (!extra.isEmpty()) {
            // 一格多件的那些(带花的花盆是盆加花两件),单列出来才对得上实扣
            List<String> parts = new ArrayList<>();
            extra.forEach((name, n) -> parts.add(name + " x" + n));
            data.put("materials_for_multi_item_cells", String.join(", ", parts));
        }
        if (!exact.isEmpty()) {
            List<String> parts = new ArrayList<>();
            exact.forEach((name, n) -> parts.add(name + " x" + n));
            data.put("materials_needing_an_exact_match", String.join(", ", parts)
                    + " — same patterns / enchantments / contents, not just the same kind of item");
        }
        data.put("layer_profile", layerProfile(byLayer));

        StringBuilder msg = new StringBuilder();
        msg.append(a.file()).append(": ").append(size.getX()).append('x').append(size.getY())
                .append('x').append(size.getZ()).append(", ").append(loaded.targets().size())
                .append(" cells, needs ").append(sum(cost)).append(" items across ")
                .append(cost.size()).append(" kinds — ").append(topLine(cost));

        if (anchored) {
            data.put("already_standing", placed);
            data.put("still_to_place", sum(remaining));
            msg.append(". At this anchor ").append(placed).append('/').append(sum(cost))
                    .append(" is already standing");
            if (WorkProfile.of(companion).freeMaterials()) {
                msg.append("; she builds free of charge in this mode");
            } else {
                Map<Item, Integer> shortOf = new LinkedHashMap<>();
                for (var e : remaining.entrySet()) {
                    int have = PlayerInv.count(companion.getInventory(), e.getKey());
                    if (have < e.getValue()) {
                        shortOf.put(e.getKey(), e.getValue() - have);
                    }
                }
                data.put("short_of", summarize(shortOf));
                msg.append(shortOf.isEmpty()
                        ? "; she is carrying enough to finish it"
                        : "; still short " + topLine(shortOf));
            }
        }
        reply.accept(TaskResult.ok(msg.toString(), data).toJson());
    }

    private static int sum(Map<Item, Integer> m) {
        int n = 0;
        for (int v : m.values()) {
            n += v;
        }
        return n;
    }

    /**
     * 按数量降序<b>列全</b>——这张单子是拿去采集的,截断了就没法用。
     *
     * <p>此前只列前十种。日式小屋有 169 种方块,模型看到十种加一句"还有 159 种"——那个
     * 数字回答不了"我该去挖什么"。全量列出来约 5KB,而这是模型<b>建之前主动调、只调一次</b>
     * 的工具,正是该花这点 token 的地方。
     *
     * <p>真正该截断的是<b>施工中途</b>的缺料回执:那个是不请自来、而且会反复出现的。
     * 两者的区别不在长短,在<b>谁在什么时候要它</b>。
     */
    private static Map<String, Object> summarize(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Integer> all = new LinkedHashMap<>();
        for (var e : sorted) {
            all.put(label(e.getKey()), e.getValue());
        }
        out.put("items", all);
        out.put("total_items", sum(counts));
        out.put("total_kinds", counts.size());
        return out;
    }

    private static String topLine(Map<Item, Integer> counts) {
        List<Map.Entry<Item, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
        List<String> parts = new ArrayList<>();
        int listed = Math.min(NAMED_IN_HEADLINE, sorted.size());
        for (int i = 0; i < listed; i++) {
            parts.add(label(sorted.get(i).getKey()) + " x" + sorted.get(i).getValue());
        }
        String head = String.join(", ", parts);
        return sorted.size() > listed
                ? head + " and " + (sorted.size() - listed) + " more kinds"
                : head;
    }

    /**
     * 每一层有多少格。这是让模型能推断"二楼大概在哪一层"的原料——只报总尺寸的话,
     * "去掉二楼"这种要求它无从下手。层数太多就分桶,免得刷屏。
     */
    private static Map<String, Integer> layerProfile(Map<Integer, Integer> byLayer) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (byLayer.isEmpty()) {
            return out;
        }
        int levels = byLayer.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        int bucket = Math.max(1, (levels + MAX_LAYERS - 1) / MAX_LAYERS);
        for (var e : byLayer.entrySet()) {
            int lo = (e.getKey() / bucket) * bucket;
            String key = bucket == 1 ? ("y+" + lo) : ("y+" + lo + ".." + (lo + bucket - 1));
            out.merge(key, e.getValue(), Integer::sum);
        }
        return out;
    }

    private static String label(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath();
    }
}
