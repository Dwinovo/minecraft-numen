package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.scan.BlockGroups;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.BlockSearch;
import com.dwinovo.numen.core.scan.GroupBook;
import com.dwinovo.numen.core.task.CompassUtil;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Gate;
import com.dwinovo.numen.permission.Permission;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code scan blocks} implementation (declared, and promoted to {@code scan_blocks}, in
 * {@link com.dwinovo.numen.core.tools.perception.ScanCommands}). It is an async (budget-sliced) server job: the method
 * takes the live entity plus a reply {@link Consumer} and returns void — the
 * result arrives on a later tick through the callback.
 *
 * <p>结果按团给出({@link BlockGroups}):每一格先拿挖掘落点会提交的同一个动作问权限层,相连且说法相同的格子
 * 成一团;最近的那些团记进身体上的团编号簿({@link GroupBook}),供 {@code mine groups} 取用。
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 192;
    /**
     * 回执最多给出多少团(离她最近的那些),其余只计数。团比格子粗:从前按格给 32 个,常常只是三四棵树的
     * 原木;16 团覆盖的地面更大,字数却相当。
     */
    static final int MAX_GROUPS = 16;
    /**
     * 不超过这么多格的团逐格列出坐标,更大的只给摘要(格数、最近一格、包围盒)。要够一圈末地传送门框架的
     * 12 格(stronghold_finding 靠它找门),够一棵普通树或一小撮矿;再多就是清单不是事实了。
     */
    static final int LIST_CELLS_UP_TO = 16;

    public void scanBlocks(
int radius,
List<String> block_ids,
            NumenPlayer self, Consumer<String> reply) {
        int r = Math.clamp(radius, MIN_RADIUS, MAX_RADIUS);
        Set<Block> targets = ToolParse.parseBlocks(block_ids);
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("no valid block_ids provided");
        }
        if (!(self.level() instanceof ServerLevel sl)) {
            throw new IllegalArgumentException("not on a server level");
        }
        BlockPos center = self.blockPosition();
        Judged judged = new Judged(self, sl, targets);
        // want 取收集上限:团要整团给,不能在"最近的几格已经证明"时就停,走满半径,内存由上限兜住
        BlockSearch.start(self.getUUID(), sl, center, r, BlockSearch.MAX_COLLECT, targets, judged,
                result -> reply.accept(buildResult(result, judged.groups, r, center, GroupBook.of(self))));
    }

    /**
     * 分团前的逐格处理:现读这一格(走完之后可能已经变了,不再是目标的不算),拿挖掘落点会提交的同一个
     * 动作 {@link Action#breakBlock} 在主线程对活世界问权限层({@link Gate#judgeLive}),连同说法收进分团。
     * 一次扫描取一份裁决快照,和任务动手前逐个裁决一批动作是同一种问法。
     */
    private static final class Judged implements Consumer<BlockScanner.Hit> {
        private final NumenPlayer self;
        private final ServerLevel level;
        private final Set<Block> targets;
        final BlockGroups groups = new BlockGroups();
        private Gate gate;

        Judged(NumenPlayer self, ServerLevel level, Set<Block> targets) {
            this.self = self;
            this.level = level;
            this.targets = targets;
        }

        @Override
        public void accept(BlockScanner.Hit hit) {
            BlockState live = level.getBlockState(hit.pos());
            if (!targets.contains(live.getBlock())) {
                return;
            }
            if (gate == null) {
                gate = Permission.gateFor(self);
            }
            groups.add(hit.pos(), live, gate.judgeLive(Action.breakBlock(hit.pos(), live), level));
        }
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

    private static String buildResult(BlockSearch.ScanResult res, BlockGroups groups, int radius, BlockPos center,
                                      GroupBook book) {
        BlockGroups.Grouped grouped = groups.grouped(center, MAX_GROUPS);
        List<BlockGroups.Group> nearest = grouped.nearest();
        List<String> ids = book.replace(nearest.stream().map(BlockGroups.Group::cells).toList());
        JsonArray out = new JsonArray();
        for (int i = 0; i < nearest.size(); i++) {
            out.add(groupJson(ids.get(i), nearest.get(i), center));
        }
        JsonObject root = new JsonObject();
        root.add("groups", out);
        // A total only when the walk actually covered the sphere. Cut short — hit the section cap or
        // the collect cap, skipped unloaded ground — whatever it saw is an artifact of stopping,
        // and a number in this slot gets read as "that is how much is there".
        if (res.coveredEverything()) {
            root.addProperty("groups_total", grouped.total());
        }
        root.addProperty("truncated", grouped.total() > nearest.size() || !res.coveredEverything());
        root.addProperty("radius_searched", radius);
        String note = coverageNote(res);
        if (note != null) {
            root.addProperty("note", note);
        }
        root.add("center", xyz(center));
        return root.toString();
    }

    /**
     * 一团的事实:编号、各种方块的格数、离她最近的一格(方向与距离)、包围盒、挖它权限层怎么说(不是放行时
     * 附上理由)、流体团的源头格数;小团逐格列坐标。包围盒与坐标写成 {@code avoid_break} 认的格式。
     */
    static JsonObject groupJson(String id, BlockGroups.Group group, BlockPos center) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("cells", group.cells().size());
        JsonObject blocks = new JsonObject();
        for (Map.Entry<Block, Integer> e : group.counts().entrySet()) {
            blocks.addProperty(BuiltInRegistries.BLOCK.getKey(e.getKey()).toString(), e.getValue());
        }
        o.add("blocks", blocks);
        JsonObject nearest = xyz(group.nearest());
        nearest.addProperty("direction", direction(center, group.nearest()));
        nearest.addProperty("distance", Math.round(group.distance() * 10) / 10.0);
        o.add("nearest", nearest);
        o.addProperty("box", cell(group.min()) + RouteSpecFlags.BOX_SEPARATOR + cell(group.max()));
        o.addProperty("permission", group.verdict().kind().name().toLowerCase(Locale.ROOT));
        if (!group.verdict().allowed()) {
            o.addProperty("reason", group.verdict().reason());
        }
        // Source vs flowing is THE decision bit for fluids: obsidian casting and
        // bucket-filling both demand a source cell.
        if (group.fluidCells() > 0) {
            o.addProperty("sources", group.sources());
        }
        if (group.cells().size() <= LIST_CELLS_UP_TO) {
            JsonArray positions = new JsonArray();
            for (BlockPos p : group.cells().keySet()) {
                positions.add(cell(p));
            }
            o.add("positions", positions);
        }
        return o;
    }

    /** 从中心看那一格:水平方位({@link CompassUtil})加上下几格;正好在中心是 {@code here}。 */
    private static String direction(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dy = to.getY() - from.getY();
        int dz = to.getZ() - from.getZ();
        List<String> parts = new ArrayList<>(2);
        if (dx != 0 || dz != 0) {
            parts.add(CompassUtil.compass(dx, dz));
        }
        if (dy != 0) {
            parts.add(Math.abs(dy) + (dy > 0 ? " up" : " down"));
        }
        return parts.isEmpty() ? "here" : String.join(", ", parts);
    }

    private static String cell(BlockPos p) {
        return p.getX() + "," + p.getY() + "," + p.getZ();
    }

    private static JsonObject xyz(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }

}
