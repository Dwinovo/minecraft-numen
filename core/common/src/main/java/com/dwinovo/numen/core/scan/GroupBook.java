package com.dwinovo.numen.core.scan;

import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 一个同伴的团编号簿:最近一次 {@code scan_blocks} 列出的团,每团一个短编号(g1、g2……),供
 * {@code mine groups} 取用;连同那一次的回执清单,{@code --page} 翻的就是它。只存数据——格子还在不在、许不许挖,
 * 由取用它的任务判。
 *
 * <p>挂在身体上({@link NumenPlayer#state}):身体没了簿子跟着没,休眠回来是空簿子。每次扫描整本换成新结果;
 * 编号的数字取自身体上落盘的编号({@link NumenPlayer#nextIdNumber}),休眠、重启之后也接着往上数、不回到
 * g1——模型手里的旧编号永远不会悄悄指向新扫描里的另一团;拿旧编号来取,{@link #staleMessage} 明说它过期了。
 *
 * <p>和路线簿({@code RouteBook})共用的是身体上的两样机器:挂簿子的 {@link NumenPlayer#state} 与编号的数字
 * {@link NumenPlayer#nextIdNumber}。两本簿子的存取规则不同——路线簿按容量淘汰、取走即划掉,团簿每次整本
 * 替换、取用不划掉——没有别的可以抽出来共用。
 */
public final class GroupBook {

    /** 这具身体的团编号簿(首次取时建)。 */
    public static GroupBook of(NumenPlayer companion) {
        return companion.state(GroupBook.class, () -> new GroupBook(companion::nextIdNumber));
    }

    /** 最新一次扫描的团:编号 → 每一格和扫描时记下的方块。 */
    private final Map<String, Map<BlockPos, Block>> latest = new LinkedHashMap<>();
    /** 编号的数字从这里取。 */
    private final LongSupplier numbers;
    /** 这本簿子记过扫描结果没有。 */
    private boolean scanned;
    /** 最新一次扫描的回执清单(它的 {@link Listing#again} 就是那一次扫描的那一行)与不随页变的小结;翻页只翻这一次。 */
    private Listing listing;
    private Map<String, Object> summary;

    public GroupBook(LongSupplier numbers) {
        this.numbers = numbers;
    }

    /** 换成一次新扫描的团,按给出的顺序编号;返回的编号与 {@code groups} 一一对应。 */
    public List<String> replace(List<Map<BlockPos, Block>> groups) {
        latest.clear();
        scanned = true;
        List<String> ids = new ArrayList<>(groups.size());
        for (Map<BlockPos, Block> cells : groups) {
            String id = "g" + numbers.getAsLong();
            latest.put(id, cells);
            ids.add(id);
        }
        return ids;
    }

    /** 记下这一次扫描的回执清单,与不随页变的小结。 */
    public void listed(Listing listing, Map<String, Object> summary) {
        this.listing = listing;
        this.summary = Map.copyOf(summary);
    }

    /**
     * 最新一次扫描的某一页,不重新扫:重扫就是另一批编号。{@code query} 不是最新那一次(或还没扫过)时如实说,叫她先扫。
     *
     * @param query 要翻页的那一行(不带 {@code --page})
     */
    public String page(String query, CommandArgs args) {
        if (listing == null) {
            return TaskResult.fail("there is no scan_blocks result on me to page through (results are dropped when"
                    + " I go dormant or the server restarts) — run " + query + " without --page first").toJson();
        }
        if (!listing.again().equals(query)) {
            return TaskResult.fail("--page turns the pages of your latest scan, which was " + listing.again()
                    + "; run " + query + " without --page to scan that").toJson();
        }
        return listing.result(args, summary).toJson();
    }

    /** 这些编号里有不在最新一次扫描里的:给模型的说明;都在返回 null。 */
    public String staleMessage(Collection<String> ids) {
        List<String> stale = new ArrayList<>();
        for (String id : ids) {
            if (!latest.containsKey(id)) {
                stale.add(id);
            }
        }
        if (stale.isEmpty()) {
            return null;
        }
        String named = String.join(", ", stale);
        if (!scanned) {
            return "there is no scan_blocks result on me to take group " + named + " from (scan results are"
                    + " dropped when I go dormant or the server restarts; ids are never reused) — scan_blocks"
                    + " first and work_mine the groups it lists.";
        }
        if (latest.isEmpty()) {
            return "group " + named + " is not from your latest scan_blocks, which found no groups — ids only"
                    + " stay good until the next scan; scan_blocks again and work_mine the groups it lists.";
        }
        List<String> listed = new ArrayList<>(latest.keySet());
        String range = listed.size() == 1 ? listed.get(0) : listed.get(0) + " to " + listed.get(listed.size() - 1);
        return "group " + named + " is not from your latest scan_blocks, which listed " + range + " — ids only"
                + " stay good until the next scan; scan_blocks again and work_mine the groups it lists.";
    }

    /** 这些团的格子与扫描时记下的方块,按编号顺序合并。先用 {@link #staleMessage} 查过。 */
    public Map<BlockPos, Block> cells(Collection<String> ids) {
        Map<BlockPos, Block> out = new LinkedHashMap<>();
        for (String id : ids) {
            out.putAll(latest.get(id));
        }
        return out;
    }
}
