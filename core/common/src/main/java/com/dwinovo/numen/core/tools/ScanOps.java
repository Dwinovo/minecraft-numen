package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.ScanBlocksJob;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The {@code scan_blocks} implementation — the business half of
 * {@code ScanBlocksTool}. It is an async (budget-sliced) server job: the method
 * takes the live entity plus a reply {@link Consumer} and returns void — the
 * result arrives on a later tick through the callback.
 */
public final class ScanOps {

    private static final int MIN_RADIUS = 1;
    private static final int MAX_RADIUS = 192;
    private static final int MAX_RESULTS = 32;

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
        ScanBlocksJob.start(self.getUUID(), sl, center, r, MAX_RESULTS, targets,
                result -> reply.accept(buildResult(result, r, center)));
    }

    /**
     * What the scan actually covered, in the model's words — {@code null} when it
     * covered everything asked for. A hit list on its own can't distinguish "no
     * iron within 192 blocks" from "most of that sphere was never looked at", and
     * the model will read the first meaning into silence every time.
     */
    static String coverageNote(ScanBlocksJob.ScanResult res) {
        List<String> notes = new ArrayList<>(2);
        if (res.deadlineHit()) {
            notes.add("time budget hit after " + res.columnsScanned() + "/" + res.columnsTotal()
                    + " chunk columns — what came back is the area nearest you; "
                    + "retry for fresh coverage or scan smaller");
        }
        if (res.columnsUnloaded() > 0) {
            notes.add(res.columnsUnloaded() + " of " + res.columnsTotal() + " chunk columns in this "
                    + "radius are not loaded, so they were not searched — blocks out there are "
                    + "UNKNOWN, not absent; walk that way and scan again to find out");
        }
        return notes.isEmpty() ? null : String.join("; ", notes);
    }

    private static String buildResult(ScanBlocksJob.ScanResult res, int radius, BlockPos center) {
        List<BlockScanner.Hit> matches = res.matches();
        int limit = Math.min(matches.size(), MAX_RESULTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < limit; i++) {
            BlockScanner.Hit s = matches.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("x", s.pos().getX());
            o.addProperty("y", s.pos().getY());
            o.addProperty("z", s.pos().getZ());
            o.addProperty("block", BuiltInRegistries.BLOCK.getKey(s.state().getBlock()).toString());
            o.addProperty("distance", s.distance());
            // Source vs flowing is THE decision bit for fluids: obsidian casting
            // and bucket-filling both demand a source cell.
            if (!s.state().getFluidState().isEmpty()) {
                o.addProperty("source", s.state().getFluidState().isSource());
            }
            out.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("matches", out);
        // Seen, not existing: the walk stops as soon as the nearest MAX_RESULTS are provably
        // the nearest, so this counts what it took to prove that — never "how much is out there".
        root.addProperty("matches_seen", matches.size());
        root.addProperty("truncated", matches.size() > MAX_RESULTS || !res.coveredEverything());
        root.addProperty("radius_searched", radius);
        String note = coverageNote(res);
        if (note != null) {
            root.addProperty("note", note);
        }
        JsonObject centerJson = new JsonObject();
        centerJson.addProperty("x", center.getX());
        centerJson.addProperty("y", center.getY());
        centerJson.addProperty("z", center.getZ());
        root.add("center", centerJson);
        return root.toString();
    }

}
