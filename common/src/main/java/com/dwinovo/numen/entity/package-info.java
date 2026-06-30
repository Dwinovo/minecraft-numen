/**
 * <strong>Public API:</strong> {@link NumenPlayer} only — the server-side
 * companion body a tool acts on (query its state, drive it, read its inventory).
 *
 * <p>The rest of this package is {@link com.dwinovo.numen.api.Internal @Internal}:
 * companion lifecycle ({@link Companions}), creation / indexing
 * ({@link CompanionFactory}, {@link CompanionRegistry}), the dev command
 * ({@link NumenCommands}) and the fake network connection ({@link FakeConnection}).
 */
package com.dwinovo.numen.entity;
