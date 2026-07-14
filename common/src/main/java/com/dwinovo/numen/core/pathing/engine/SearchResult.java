package com.dwinovo.numen.core.pathing.engine;

import java.util.List;

/**
 * The outcome of one {@link PathSearch#run()}.
 *
 * <ul>
 *   <li>{@link Kind#COMPLETE} — the goal was reached; {@code edges} is the full
 *       route, {@code end} satisfies the goal predicate.</li>
 *   <li>{@link Kind#PARTIAL_COMMIT} — budget exhausted without reaching the
 *       goal, but a committable candidate existed (≥ the commit-distance gate
 *       from the start AND genuinely nearer the goal by effective h);
 *       {@code edges} is the walkable route to it. The caller walks it and
 *       replans from {@code end}.</li>
 *   <li>{@link Kind#NO_PATH} — nothing committable; {@code edges} empty,
 *       {@code end == start}. Consult {@link #stats} for the autopsy.</li>
 *   <li>{@link Kind#CANCELLED} — cooperatively cancelled; {@code edges} empty.
 *       A cancelled search never writes to the learning table.</li>
 * </ul>
 *
 * @param <E> the opaque edge payload (MC side: {@code Movement}).
 */
public final class SearchResult<E> {

    public enum Kind { COMPLETE, PARTIAL_COMMIT, NO_PATH, CANCELLED }

    public final Kind kind;
    /** Packed start position. */
    public final long start;
    /** Packed end position; {@code == start} for NO_PATH / CANCELLED. */
    public final long end;
    /** Edges ordered start → end; empty for NO_PATH / CANCELLED. Immutable. */
    public final List<E> edges;
    public final SearchStats stats;

    private SearchResult(Kind kind, long start, long end, List<E> edges, SearchStats stats) {
        this.kind = kind;
        this.start = start;
        this.end = end;
        this.edges = List.copyOf(edges);
        this.stats = stats;
    }

    public static <E> SearchResult<E> complete(long start, long end, List<E> edges, SearchStats stats) {
        return new SearchResult<>(Kind.COMPLETE, start, end, edges, stats);
    }

    public static <E> SearchResult<E> partialCommit(long start, long end, List<E> edges, SearchStats stats) {
        return new SearchResult<>(Kind.PARTIAL_COMMIT, start, end, edges, stats);
    }

    public static <E> SearchResult<E> noPath(long start, SearchStats stats) {
        return new SearchResult<>(Kind.NO_PATH, start, start, List.of(), stats);
    }

    public static <E> SearchResult<E> cancelled(long start, SearchStats stats) {
        return new SearchResult<>(Kind.CANCELLED, start, start, List.of(), stats);
    }
}
