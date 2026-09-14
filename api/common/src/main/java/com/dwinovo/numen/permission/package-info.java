/**
 * <strong>Public API:</strong> the permission layer — the one place that answers "may the body
 * do this to the world". Content packs send an {@link com.dwinovo.numen.permission.Action}
 * through {@link com.dwinovo.numen.permission.Permission} (live, main thread) or a
 * {@link com.dwinovo.numen.permission.Gate} snapshot (any thread) and read the
 * {@link com.dwinovo.numen.permission.Verdict}; nothing else in the repository decides
 * whether a block may be broken, placed, or an entity attacked.
 *
 * <p>Rules are data ({@link com.dwinovo.numen.permission.Rule},
 * {@link com.dwinovo.numen.permission.RuleSet}), signals are functions
 * ({@link com.dwinovo.numen.permission.Signals}), the placement record is world-saved
 * ({@link com.dwinovo.numen.permission.PlacedBlocks}), the per-companion mode lives with its
 * owner ({@link com.dwinovo.numen.permission.PermissionStore}). Territory mods answer through
 * {@link com.dwinovo.numen.permission.TerritoryClaims}. When a verdict asks, the task that proposed the
 * action consults the owner through the companion's
 * {@link com.dwinovo.numen.permission.ConsentDesk}; what the owner allows becomes a task-scoped grant that
 * feeds back into the next {@link com.dwinovo.numen.permission.Gate}. Design: {@code docs/permission-layer.md}.
 */
package com.dwinovo.numen.permission;
