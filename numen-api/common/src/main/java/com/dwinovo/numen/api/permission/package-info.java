/**
 * <strong>Public API:</strong> the permission layer — the one place that answers "may the body
 * do this to the world". Content packs send an {@link com.dwinovo.numen.api.permission.Action}
 * through {@link com.dwinovo.numen.api.permission.Permission} (live, main thread) or a
 * {@link com.dwinovo.numen.api.permission.Gate} snapshot (any thread) and read the
 * {@link com.dwinovo.numen.api.permission.Verdict}; nothing else in the repository decides
 * whether a block may be broken, placed, or an entity attacked.
 *
 * <p>Rules are data ({@link com.dwinovo.numen.api.permission.Rule},
 * {@link com.dwinovo.numen.api.permission.RuleSet}), signals are functions
 * ({@link com.dwinovo.numen.api.permission.Signals}), the placement record is world-saved
 * ({@link com.dwinovo.numen.api.permission.PlacedBlocks}), the per-companion mode and the owner's own rule
 * layer — checked before the factory layer — live with the owner
 * ({@link com.dwinovo.numen.api.permission.PermissionStore}). When a verdict asks, the task that proposed the
 * action consults the owner through the companion's
 * {@link com.dwinovo.numen.api.permission.ConsentDesk}; what the owner allows becomes a task-scoped grant that
 * feeds back into the next {@link com.dwinovo.numen.api.permission.Gate}, and "allow and remember" also writes
 * an allow row into the owner's layer. Design: {@code docs/permission-layer.md}.
 */
package com.dwinovo.numen.api.permission;
