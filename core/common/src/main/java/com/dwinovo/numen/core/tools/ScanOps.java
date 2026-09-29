package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.area.Area;
import com.dwinovo.numen.area.AreaRef;
import com.dwinovo.numen.area.Cells;
import com.dwinovo.numen.cli.CommandArgs;
import com.dwinovo.numen.cli.Listing;
import com.dwinovo.numen.cli.ServerSource;
import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockScan;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code scan blocks} 的实现(登记、提升成 {@code scan_blocks} 在 {@link com.dwinovo.numen.core.tools.perception.ScanCommands})。
 * 看是按刻分片的({@link BlockScan}),回执在看完的那一刻经这次调用的回信口送出。
 *
 * <p>结果按团给出:每一格先拿挖掘落点会提交的同一个动作问权限层,相连且说法相同的格子成一团,由近及远,一团一行。
 * 不带 {@code --into} 只是看:团没有编号,什么也不存,翻页就是再看一次。带 {@code --into <区域>} 就把每一团加成那块区域的一部分
 * (编号 {@code g} 在区域里续),回执里每团的编号就是能拿去点名的 {@code 区域/g5};改区域是动作 {@code edit_area},看之前先过
 * 权限层。{@code --in <区域>} 只收落在那块区域里的格,半径照旧是从她脚下看多远。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;

    private ScanOps() {}

    /**
     * 看一次,回执是结果的第一页(或 {@code --page} 要的那一页)。
     *
     * @param in    只看这块区域(或它的一部分)里的;不限为 null
     * @param into  把每一团加进这块区域;只是看为 null
     * @param again 这一次看本身的那一行(不带 {@code --page}):翻页提示写它,改区域的征询也点名它
     */
    public static void scanBlocks(ServerSource src, int radius, List<String> blockIds, AreaRef in, AreaRef into,
                                  CommandArgs args, String again) {
        NumenPlayer self = src.companion();
        int r = Math.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS);
        Set<Block> targets = ToolParse.parseBlocks(blockIds);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        Area only = in == null ? null : AreaOps.resolve(self, in);
        if (into == null) {
            BlockScan.start(self, r, targets, only, found -> src.reply(listed(found, r, in, null, null, args, again)));
            return;
        }
        if (args.get(Listing.PAGE) != null) {
            throw new IllegalArgumentException("--page turns the pages of a scan that only looks; the groups a scan "
                    + "added to " + into + " are its parts, and area show " + into + " --page " + args.get(Listing.PAGE)
                    + " lists them");
        }
        String name = into.name();
        if (into.part() != null) {
            throw new IllegalArgumentException("--into takes a whole area (" + name + "): each group becomes a new part "
                    + "of it");
        }
        AreaOps.existing(self, name);
        src.authorize(Action.editArea(name), again, allowed -> BlockScan.start(self, r, targets, only,
                found -> allowed.reply(added(self, found, r, in, name, args))));
    }

    /** 看完,写进区域:那一刻的区域加上每一团。看的时候区域被删了就不写,照实说。 */
    private static String added(NumenPlayer self, BlockScan.Found found, int radius, AreaRef in, String into,
                                CommandArgs args) {
        Area now = AreaOps.store(self).get(into);
        if (now == null) {
            return TaskResult.fail("area " + into + " was deleted while I was scanning, so nothing was added; area new "
                    + into + " makes it again").toJson();
        }
        BlockScan.Added added = found.into(now);
        AreaOps.store(self).replace(into, added.area());
        return listed(found, radius, in, into, added.ids(), args, "area show " + into);
    }

    /**
     * What the scan actually covered, in the model's words — {@code null} when it
     * covered everything asked for. A group list on its own can't distinguish "no
     * iron within 192 blocks" from "most of that sphere was never looked at", and
     * the model will read the first meaning into silence every time.
     */
    static String coverageNote(BlockSearch.ScanResult res) {
        List<String> notes = new ArrayList<>(3);
        String capped = res.sectionCapNote();
        if (capped != null) {
            notes.add(capped);
        }
        if (res.collectCapHit()) {
            notes.add("stopped at " + BlockSearch.MAX_COLLECT + " matching blocks — only the area nearest you "
                    + "was read and groups at its edge may be cut off; scan a smaller radius");
        }
        if (res.columnsUnloaded() > 0) {
            notes.add(res.columnsUnloaded() + " of " + res.columnsTotal() + " chunk columns in this "
                    + "radius are not loaded, so they were not searched — blocks out there are "
                    + "UNKNOWN, not absent; walk that way and scan again to find out");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    /**
     * 一次看的回执:抬头说在哪、多远、找到几团(写进了区域就说加成了哪几部分);没看全时抬头只说"读到的那部分里"有几团,结尾说清
     * 哪里没读到。一团一行,按输出预算分页。{@code data} 是整次的小结,不随页变。
     *
     * @param ids   写进区域后各团的编号;只是看为 null
     * @param again 翻页提示写的那一行
     */
    private static String listed(BlockScan.Found found, int radius, AreaRef in, String into, List<String> ids,
                                 CommandArgs args, String again) {
        List<BlockGroups.Group> all = found.groups();
        List<String> rows = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) {
            rows.add(groupJson(ids == null ? null : into + "/" + ids.get(i), all.get(i), found.center(), found.tick())
                    .toString());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        // A total only when the walk actually covered the sphere. Cut short — hit the section cap or
        // the collect cap, skipped unloaded ground — whatever it saw is an artifact of stopping,
        // and a number in this slot gets read as "that is how much is there". The head says the
        // same thing in words.
        BlockSearch.ScanResult res = found.coverage();
        boolean whole = res.coveredEverything();
        String note = coverageNote(res);
        String center = AreaText.cell(found.center());
        String where = (whole
                ? " within " + radius + " blocks of " + center
                : " in the part of the " + radius + "-block radius around " + center + " that was read"
                        + (note == null ? "" : " (the note at the end says what was not)"))
                + (in == null ? "" : ", inside area " + in);
        if (whole) {
            data.put("groups_total", all.size());
        }
        data.put("radius_searched", radius);
        if (into != null) {
            data.put("area", into);
        }
        String kept = ids == null || ids.isEmpty() ? ""
                : ", added to area " + into + " as " + (ids.size() == 1 ? ids.get(0)
                        : ids.get(0) + " to " + ids.get(ids.size() - 1));
        String head = all.isEmpty()
                ? "No groups" + where + (into == null ? "." : "; nothing was added to area " + into + ".")
                : all.size() + " group(s)" + where + kept + ", nearest first, one per line:";
        return new Listing(head, rows, note == null ? "" : "Note: " + note, again).result(args, data).toJson();
    }

    /**
     * 一团的事实:与区域一部分同一种一行({@link AreaText#part},格子附带看到的方块),接上挖它权限层怎么说(不是放行时附上理由)。
     *
     * @param id     写进区域后的编号({@code ores/g5});只是看为 null
     * @param center 看的中心,也就是她当时脚下那一格:方向与距离从这里量
     */
    static JsonObject groupJson(String id, BlockGroups.Group group, BlockPos center, long tick) {
        JsonObject o = AreaText.part(id, Cells.seen(group.cells(), tick), center);
        o.addProperty("permission", group.verdict().kind().name().toLowerCase(Locale.ROOT));
        if (!group.verdict().allowed()) {
            o.addProperty("reason", group.verdict().reason());
        }
        return o;
    }
}
