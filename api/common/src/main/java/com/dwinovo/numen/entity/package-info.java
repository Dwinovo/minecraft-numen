/**
 * <strong>Public API:</strong> {@link NumenPlayer} — the server-side companion
 * body a tool acts on (query its state, drive it, read its inventory) — and
 * {@link InputDriver}, its input surface (look at, press keys, halt): together
 * they are how a tool pack moves the body without touching engine internals.
 *
 * <p>The rest of this package is {@link com.dwinovo.numen.api.Internal @Internal}:
 * companion lifecycle ({@link Companions}), creation / indexing
 * ({@link CompanionFactory}, {@link CompanionRegistry}), the dev command
 * ({@link NumenCommands}) and the fake network connection ({@link FakeConnection}).
 */
package com.dwinovo.numen.entity;
