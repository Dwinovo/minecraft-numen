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
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
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
 * (编号 {@code g} 在区域里续;没有这块区域就新建它,像 shell 的 {@code >}),回执里每团的编号就是能拿去点名的 {@code 区域/g5};
 * 改区域与建区域都是动作 {@code edit_area},看之前先过权限层。{@code --in <区域>} 只收落在那块区域里的格,半径照旧是从她脚下看多远。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    /**
     * 扫进区域时回执列出的团数:最近的这几团够她定下一步挖哪儿、走哪儿;全部已在区域里,{@code area show} 一页一页列。
     */
    private static final int INTO_SHOWN = 5;

    private ScanOps() {}

    /**
     * 看一次,回执是结果的第一页(或 {@code page} 要的那一页)。
     *
     * @param in   只看这块区域(或它的一部分)里的;不限为 null
     * @param into 把每一团加进这块区域(没有就新建);只是看为 null
     * @param what 这次调用写成脚本里的样子:改区域的征询点名它
     */
    public static void scanBlocks(ServerSource src, int radius, List<String> blockIds, AreaRef in, AreaRef into,
                                  CommandArgs args, String what) {
        NumenPlayer self = src.companion();
        int r = Math.clamp(radius, MIN_RADIUS, BlockScan.MAX_RADIUS);
        Set<Block> targets = ToolParse.parseBlocks(blockIds);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        Area only = in == null ? null : AreaOps.resolve(self, in);
        if (into == null) {
            BlockScan.start(self, r, targets, only, found -> src.reply(listed(found, r, in, null, false, null, args)));
            return;
        }
        if (args.get(Listing.PAGE) != null) {
            throw new IllegalArgumentException("page turns the pages of a scan that only looks; the groups a scan "
                    + "added to " + into + " are its parts, and `area.show(\"" + into + "\", {page = "
                    + args.get(Listing.PAGE) + "})` lists them");
        }
        String name = into.name();
        if (into.part() != null) {
            throw new IllegalArgumentException("into takes a whole area (" + name + "): each group becomes a new part "
                    + "of it");
        }
        AreaOps.into(self, name);
        ResourceKey<Level> dimension = self.level().dimension();
        src.authorize(Action.editArea(name), what, allowed -> BlockScan.start(self, r, targets, only,
                found -> allowed.reply(added(self, found, r, in, name, dimension, args))));
    }

    /** 看完,写进区域:那一刻的区域加上每一团;那一刻没有这块区域就新建它(在看的那个维度),回执说新建了。 */
    private static String added(NumenPlayer self, BlockScan.Found found, int radius, AreaRef in, String into,
                                ResourceKey<Level> dimension, CommandArgs args) {
        Area now = AreaOps.store(self).get(into);
        boolean made = now == null;
        BlockScan.Added added = found.into(made ? Area.empty(dimension) : now);
        if (made) {
            AreaOps.store(self).create(into, added.area());
        } else {
            AreaOps.store(self).replace(into, added.area());
        }
        return listed(found, radius, in, into, made, added.ids(), args);
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
     * 哪里没读到。只是看的一团一行,按 {@link AreaText#PAGE_BYTES} 分页;扫进区域的只列最近 {@value #INTO_SHOWN} 团,抬头说全部
     * 在哪、怎么看。{@code data} 是整次的小结,不随页变。
     *
     * @param made  区域是这一次新建的
     * @param ids   写进区域后各团的编号;只是看为 null
     */
    private static String listed(BlockScan.Found found, int radius, AreaRef in, String into, boolean made,
                                 List<String> ids, CommandArgs args) {
        List<BlockGroups.Group> all = found.groups();
        // 扫进区域的,回执只列最近几团:全部都在区域里,细节归 area show
        int shown = ids == null ? all.size() : Math.min(INTO_SHOWN, all.size());
        List<String> rows = new ArrayList<>(shown);
        JsonArray groups = new JsonArray();
        for (int i = 0; i < all.size(); i++) {
            JsonObject group = groupJson(ids == null ? null : into + "/" + ids.get(i), all.get(i), found.center(),
                    found.tick());
            groups.add(group);
            if (i < shown) {
                rows.add(group.toString());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("groups", groups);
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
        data.put("complete", whole);
        if (note != null) {
            data.put("note", note);
        }
        data.put("radius", radius);
        if (into != null) {
            data.put("area", into);
        }
        String area = made ? "the new area " + into + " (made just now)" : "area " + into;
        String kept = ids == null || ids.isEmpty() ? ""
                : ", added to " + area + " as " + (ids.size() == 1 ? ids.get(0)
                        : ids.get(0) + " to " + ids.get(ids.size() - 1));
        String order = shown < all.size()
                ? "; the nearest " + shown + " follow, one per line (area.show(\"" + into + "\") lists every part, "
                        + "work.dig(\"" + into + "\") digs them):"
                : ", nearest first, one per line:";
        String head = all.isEmpty()
                ? "No groups" + where + (into == null ? "."
                        : made ? "; made area " + into + ", still empty." : "; nothing was added to area " + into + ".")
                : all.size() + " group(s)" + where + kept + order;
        return new Listing(head, rows, note == null ? "" : "Note: " + note, AreaText.PAGE_BYTES)
                .result(args, data).toJson();
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
