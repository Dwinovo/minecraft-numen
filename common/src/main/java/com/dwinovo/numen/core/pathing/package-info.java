/**
 * Server-side navigation for the companion body: planning, movement generation,
 * and physical execution for a fake {@code ServerPlayer} in a modifiable world.
 *
 * <h2>Architecture</h2>
 * <ul>
 *   <li>{@code engine/} — the planning core: a Minecraft-free weighted A* with a
 *       two-phase expansion budget, cross-segment heuristic learning after
 *       Real-Time Adaptive A* (Koenig &amp; Likhachev, AAMAS 2006), and a
 *       distance-gated partial-path commitment rule; unit-tested against
 *       synthetic worlds ({@code common/src/test/.../pathing/engine/}).</li>
 *   <li>{@code calc/} — the Minecraft adapter: world/inventory snapshots
 *       ({@code NavContext}), goal algebra ({@code NavGoal}), and the
 *       {@code EngineSearch} bridge between packed-long engine positions and
 *       {@code BlockPos}/{@code Movement}.</li>
 *   <li>{@code movement/} — the move generators: which single-step actions exist
 *       from a given cell (walk, ascend, descend, pillar, dig, parkour, …) and
 *       what each costs in game ticks, tool- and inventory-aware.</li>
 *   <li>{@code exec/} — physical execution on the live server: walking a planned
 *       path tick by tick, native block breaking/placing through the same server
 *       entry points a real client's packets reach, with per-move re-costing,
 *       stall detection, and replanning.</li>
 * </ul>
 *
 * <h2>Provenance</h2>
 * Everything in this package is implemented from scratch for the server-side
 * fake-player setting; no third-party pathfinding source has been copied, ported,
 * or adapted. Movement mechanics and several cost constants follow techniques
 * that are long-established in open-source Minecraft automation (notably the
 * publicly documented behavior of the Baritone client pathfinder and of
 * fake-player action packs), re-derived here against the server API — that
 * intellectual debt is acknowledged once, in this file and in the project
 * README, rather than line by line. The {@code engine/} planning core departs
 * from that lineage entirely: its budget model, commitment rule, and learned
 * heuristic have no counterpart in those projects.
 */
package com.dwinovo.numen.core.pathing;
