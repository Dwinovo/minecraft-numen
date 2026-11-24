/**
 * <strong>Public API:</strong> {@link NumenPlayer} — the server-side companion
 * body a tool acts on (query its state, drive it, read its inventory) — and the four things a real
 * player has in hand that she, with no client, lacks: her keys ({@link NumenPlayer#controls}), her view
 * ({@link NumenPlayer#look}), her mouse ({@link NumenPlayer#mouse}: left-click dig and right-click use on the block or
 * entity the driver names, landing whenever it is within reach, every press through the permission layer) and her hotbar ({@link NumenPlayer#hotbar}). {@link InputDriver} is her
 * boat steering. Together they are how a tool pack moves and works the body without touching Numen API internals.
 *
 * <p>The vanilla formulas and geometry the body and a pathfinder share are public too: {@link DigTime} (how long a block takes
 * to dig), {@link Reach}, {@link Sight}, {@link Faces} and {@link Replaceable} (what she can touch, see,
 * which face a click lands on, and where a placed block lands). What her hand digs out goes straight into her inventory
 * ({@link Harvest}).
 *
 * <p>Which carried stack an item id means is answered in one place, {@link Stacks#pick} (the main hand's stack, else the first
 * slot; two stacks are the same kind only when item and components both match), and {@link StackLabel} is the one short text that
 * tells same-id stacks apart in the model's inventory listing and in receipts, written from what the answerers of
 * {@link com.dwinovo.numen.api.Questions#ITEM} say about the stack.
 *
 * <p>The rest of this package is {@link com.dwinovo.numen.api.Internal @Internal}:
 * companion lifecycle ({@link Companions}), creation / indexing
 * ({@link CompanionFactory}, {@link CompanionRegistry}), the dev command
 * ({@link NumenCommands}) and the fake network connection ({@link FakeConnection}).
 */
package com.dwinovo.numen.api.entity;
