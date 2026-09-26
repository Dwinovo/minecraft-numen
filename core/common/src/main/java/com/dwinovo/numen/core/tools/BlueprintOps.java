package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.blueprint.BlueprintStore;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.build.ReplaceMode;
import com.dwinovo.numen.core.tools.work.BuildTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 图纸三件事的业务半边:列出蓝图目录里的文件({@code build blueprints})、读一张图纸的尺寸与用料
 * ({@code build blueprint_read})、按图整幢施工({@code build blueprint})。
 *
 * <p>读图纸不动世界一格。这件事此前只能作为<b>失败的副产品</b>出现:得先选好位置、发起施工、被拒绝,
 * 才知道要多少料。实测就是这个样子——盖屋顶、缺四十一块楼梯、跑去找工作台、找不到、再试、再缺,
 * 全程没有任何一步能提前回答"这栋房子要多少料"。而生存模式的价值恰恰在那条链上:她设计 → 报料 →
 * 一起去采 → 施工。报料是第二环,不该靠撞墙触发。
 */
public final class BlueprintOps {

    private static final long MIN_TIMEOUT_TICKS = 2 * 60 * 20;
    /** 一句话概览里点名几种,其余只报个数——完整清单在 data 里,那才是拿去采集的。 */
    private static final int NAMED_IN_HEADLINE = 5;
    /** 按层分布最多报几层,再多就分桶。 */
    private static final int MAX_LAYERS = 24;

    private BlueprintOps() {}

    /** 蓝图目录里有哪些,各多大。 */
    public static String list(MinecraftServer server) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String name : BlueprintStore.list(server)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", name);
            try {
                Vec3i size = BlueprintStore.peekSize(server, name);
                entry.put("size", size.getX() + "x" + size.getY() + "x" + size.getZ());
            } catch (Exception e) {
                entry.put("size", "unreadable: " + e.getMessage());
            }
            out.add(entry);
        }
        String message = out.isEmpty()
                ? "no blueprints yet; drop .litematic / .schem / .nbt files into the schematics folder"
                : out.size() + " blueprint(s) available";
        return TaskResult.ok(message, Map.of("blueprints", out)).toJson();
    }

    /**
     * 按图整幢施工的那件活。{@code quarters} 是顺时针转几个 90°。料不齐时能建多少建多少(整幢图纸一趟运不完是常态),
     * 同一个调用再发一次就从断点接上。
     */
    public static BuildTaskRecord build(ServerSource src, String file, BlockPos anchor, int quarters) {
        NumenPlayer companion = src.companion();
        BlueprintStore.Loaded loaded = BlueprintStore.load(companion.serverLevel(), file, anchor, quarters);
        if (loaded.targets().isEmpty()) {
            throw new IllegalArgumentException("blueprint " + file + " contains no buildable cells");
        }
        // 材料记账随能力画像(同 build 工具):免耗材想建就建,否则消耗并预检报缺。
        boolean consume = !WorkProfile.of(companion).freeMaterials();
        long timeout = Math.max(MIN_TIMEOUT_TICKS, BuildTool.timeoutTicksFor(loaded.targets().size(), consume));
        // allowPartial:整幢图纸一趟运不完是常态,分段施工 + 精确续建
        BuildTaskRecord record = new BuildTaskRecord(src,
                companion.level().getGameTime() + timeout, loaded.targets(),
                ReplaceMode.REPLACE_EMPTY, consume, true, loaded.blockEntityData(), loaded.entities());
        // 加载时掉的格随任务一起交代:掉格必须有账,否则回执会拿剩下的格数当全部
        record.droppedAtLoad(loaded.dropped());
        // 逐格料单:带花的花盆收盆加花两件,带花纹的旗帜收一叠但要组件一致
        record.cellNeeds(loaded.cellNeeds());
        return record;
    }

    /**
     * 读一张图纸:尺寸、用料、按层分布。给了锚点({@code anchor} 非 null)再报那里已经立着多少、还差多少、她手上缺多少。
     */
    public static String read(NumenPlayer companion, String file, BlockPos anchor, int quarters) {
        boolean anchored = anchor != null;
        ServerLevel level = companion.serverLevel();
        BlueprintStore.Loaded loaded = BlueprintStore.load(level, file, anchored ? anchor : BlockPos.ZERO, quarters);

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
        // 这两项和 materials 一样出 map 而不是拼好的字符串:模型要拿它们做算术(还差
        // 几件、够不够),给字符串等于逼它先解析我们的排版。同一份数据两种形状,是给
        // 自己找的麻烦。
        if (!extra.isEmpty()) {
            // 一格多件的那些(带花的花盆是盆加花两件),单列出来才对得上实扣
            data.put("materials_for_multi_item_cells", extra);
        }
        if (!exact.isEmpty()) {
            data.put("materials_needing_an_exact_match", exact);
            data.put("exact_match_means",
                    "same patterns / enchantments / contents, not just the same kind of item");
        }
        data.put("layer_profile", layerProfile(byLayer));

        StringBuilder msg = new StringBuilder();
        msg.append(file).append(": ").append(size.getX()).append('x').append(size.getY())
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
                    // 和逐格闸门、实扣同源的 36 格口径。用 41 格的那个会让报价说"料够了"
                    // 而施工每格都判缺料——副手上那叠木板正是这么骗过报价的。
                    int have = PlayerInv.buildableCount(companion.getInventory(), e.getKey());
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
        return TaskResult.ok(msg.toString(), data).toJson();
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
