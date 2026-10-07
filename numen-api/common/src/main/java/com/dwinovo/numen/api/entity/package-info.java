/**
 * <strong>Public API:</strong> {@link NumenPlayer} — the server-side companion
 * body a tool acts on (query its state, drive it, read its inventory) — and the four things a real
 * player has in hand that she, with no client, lacks: her keys ({@link NumenPlayer#controls}), her view
 * ({@link NumenPlayer#look}), her mouse ({@link NumenPlayer#mouse}: the crosshair, left-click dig, right-click use,
 * every press through the permission layer) and her hotbar ({@link NumenPlayer#hotbar}). {@link InputDriver} is her
 * boat steering. Together they are how a tool pack moves and works the body without touching Numen API internals.
 *
 * <p>The vanilla formulas and geometry the body and a pathfinder share are public too: {@link DigTime} (how long a block takes
 * to dig and the pause after), {@link Reach}, {@link Sight}, {@link Faces} and {@link Replaceable} (what she can touch, see,
 * click on, and where a placed block lands).
 *
 * <p>The rest of this package is {@link com.dwinovo.numen.api.Internal @Internal}:
 * companion lifecycle ({@link Companions}), creation / indexing
 * ({@link CompanionFactory}, {@link CompanionRegistry}), the dev command
 * ({@link NumenCommands}) and the fake network connection ({@link FakeConnection}).
 */
package com.dwinovo.numen.api.entity;
