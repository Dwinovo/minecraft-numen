package com.dwinovo.numen.core.pathing.hier;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;

import java.util.PriorityQueue;

/**
 * A section-granular lower-ish bound on remaining travel cost, built by one
 * Dijkstra sweep out from the goal's section over summarized face connectivity
 * — the coarse layer's product, frozen per search (the engine caches h at node
 * creation, so a field must never change under a running search).
 *
 * <p>Weights: an {@code OPEN→OPEN} crossing costs one section of walking; a
 * crossing that needs digging ({@code SOFT} on either side) adds a nominal
 * dig penalty, so the field FLOWS ALONG WALKABLE CORRIDORS first — a deep
 * well's field leads up the shaft instead of radiating straight through rock.
 * That penalty makes the field deliberately, boundedly inadmissible for
 * dig-through shortcuts (the same doctrine as {@code NavGoal.near}'s
 * documented inadmissibility); the consumer caps it against the point bound,
 * and correctness always rests with the fine search + execution re-costing.
 *
 * <p>The build doubles as the reachability probe: if the sweep EXHAUSTED its
 * frontier (not capped) without touching the start's section, no crossable
 * face chain connects goal and start — {@link #sealed} — and only then, since
 * HARD faces only ever come from exact scans and unknown terrain reads as
 * open (see {@link CellSampler}), is "sealed" sound rather than guessed.
 */
public final class CoarseField {

    private final Long2DoubleMap dist;
    private final boolean startReached;
    private final boolean frontierExhausted;
    private final boolean truncated;

    private CoarseField(Long2DoubleMap dist, boolean startReached,
                        boolean frontierExhausted, boolean truncated) {
        this.dist = dist;
        this.startReached = startReached;
        this.frontierExhausted = frontierExhausted;
        this.truncated = truncated;
    }

    /** The field's bound for a CELL (global block coords); 0 where the sweep
     *  never priced the section (no information — never a lie). */
    public double boundAt(int x, int y, int z) {
        return dist.getOrDefault(Sections.ofCell(x, y, z), 0.0);
    }

    /** Goal and start share no crossable face chain — trustworthy only because
     *  the sweep ran to natural exhaustion (never claimed off a capped or
     *  sampled build). */
    public boolean sealed() {
        return !startReached && frontierExhausted && !truncated;
    }

    /** Sections priced (diagnostics). */
    public int sizeSections() {
        return dist.size();
    }

    /** The sweep hit a cap before finishing (diagnostics; a truncated field is
     *  still usable guidance, never trusted for {@link #sealed}). */
    public boolean truncated() {
        return truncated;
    }

    /** The sweep priced the start's section (diagnostics). */
    public boolean startReached() {
        return startReached;
    }

    /** One-line diagnostic summary for the dispatch log. */
    public String summary() {
        return "sections=" + dist.size()
                + (startReached ? " start-reached" : " START-NOT-REACHED")
                + (truncated ? " TRUNCATED" : "")
                + (sealed() ? " SEALED" : "");
    }

    /**
     * Build the field: multi-source Dijkstra from {@code goal…} section(s)
     * toward (and past) {@code start}'s section.
     *
     * @param walkCrossCost   cost of one OPEN→OPEN section crossing
     * @param softCrossExtra  added when either side of the crossing needs digging
     * @param sectionCap      max sections priced before giving up (truncated)
     * @param exactScanCap    max EXACT face scans spent before giving up
     */
    public static CoarseField build(CellSampler sampler, SummaryCache cache,
                                    int startX, int startY, int startZ,
                                    int goalX, int goalY, int goalZ,
                                    double walkCrossCost, double softCrossExtra,
                                    int sectionCap, int exactScanCap) {
        long startKey = Sections.ofCell(startX, startY, startZ);
        long goalKey = Sections.ofCell(goalX, goalY, goalZ);

        Long2DoubleMap dist = new Long2DoubleOpenHashMap();
        dist.defaultReturnValue(Double.NaN);
        LongSet settled = new LongOpenHashSet();
        Long2ObjectMap<SectionSummary> summaries = new Long2ObjectOpenHashMap<>();
        int[] exactScans = {0};

        record Entry(long key, double d) {}
        PriorityQueue<Entry> queue = new PriorityQueue<>((a, b) -> Double.compare(a.d, b.d));
        dist.put(goalKey, 0.0);
        queue.add(new Entry(goalKey, 0.0));

        boolean startReached = false;
        boolean truncated = false;

        while (!queue.isEmpty()) {
            Entry cur = queue.poll();
            if (!settled.add(cur.key)) {
                continue;
            }
            if (cur.key == startKey) {
                startReached = true;
                // Keep sweeping a little past the start so cells around it are
                // priced, but the cap bounds the tail anyway.
            }
            if (settled.size() > sectionCap || exactScans[0] > exactScanCap) {
                truncated = true;
                break;
            }
            SectionSummary curSum = summaryOf(sampler, cache, summaries, cur.key, exactScans);
            for (int dir = 0; dir < Directions.COUNT; dir++) {
                SectionSummary.Face myFace = curSum.face(dir);
                if (myFace == SectionSummary.Face.HARD) {
                    continue;
                }
                long nKey = Sections.pack(Sections.x(cur.key) + Directions.DX[dir],
                        Sections.y(cur.key) + Directions.DY[dir],
                        Sections.z(cur.key) + Directions.DZ[dir]);
                if (settled.contains(nKey)) {
                    continue;
                }
                SectionSummary nSum = summaryOf(sampler, cache, summaries, nKey, exactScans);
                SectionSummary.Face theirFace = nSum.face(Directions.opposite(dir));
                if (theirFace == SectionSummary.Face.HARD) {
                    continue;
                }
                boolean open = myFace == SectionSummary.Face.OPEN
                        && theirFace == SectionSummary.Face.OPEN;
                double w = walkCrossCost + (open ? 0.0 : softCrossExtra);
                double nd = cur.d + w;
                double old = dist.get(nKey);
                if (Double.isNaN(old) || nd < old) {
                    dist.put(nKey, nd);
                    queue.add(new Entry(nKey, nd));
                }
            }
        }

        return new CoarseField(dist, startReached, queue.isEmpty() && !truncated, truncated);
    }

    private static SectionSummary summaryOf(CellSampler sampler, SummaryCache cache,
                                            Long2ObjectMap<SectionSummary> local,
                                            long key, int[] exactScans) {
        SectionSummary s = local.get(key);
        if (s != null) {
            return s;
        }
        s = cache != null ? cache.get(key) : null;
        if (s == null) {
            int sx = Sections.x(key);
            int sy = Sections.y(key);
            int sz = Sections.z(key);
            boolean uniform = sampler.uniform(sx, sy, sz) != CellSampler.Uniform.MIXED_OR_UNKNOWN;
            if (!uniform) {
                exactScans[0]++;
            }
            s = SectionSummarizer.summarize(sampler, sx, sy, sz);
            if (cache != null) {
                cache.put(key, s);
            }
        }
        local.put(key, s);
        return s;
    }
}
