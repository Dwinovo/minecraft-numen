/**
 * <strong>Public API:</strong> the permission layer — the one place that answers "may the body
 * do this to the world". Content packs send an {@link com.dwinovo.numen.api.permission.Action}
 * through {@link com.dwinovo.numen.api.permission.Permission} (live, main thread) or a
 * {@link com.dwinovo.numen.api.permission.Gate} snapshot (any thread) and read the
 * {@link com.dwinovo.numen.api.permission.Verdict}; nothing else in the repository decides
 * whether a block may be broken, placed, or an entity attacked.
 *
 * <p>The rule syntax, the verdicts and the order they are decided in live in the plain-JVM module
 * {@code permission} ({@link com.dwinovo.numen.permission.Rule}, {@link com.dwinovo.numen.permission.Policy});
 * this package adapts them to the game: the words a rule can use are the built-in groups and properties
 * ({@link com.dwinovo.numen.api.permission.Groups}) plus those a plugin registers for the kinds of things it
 * knows ({@link com.dwinovo.numen.api.permission.RuleWord}), whose a block or an entity is comes from whoever
 * registered to answer that ({@link com.dwinovo.numen.api.permission.BlockOwner},
 * {@link com.dwinovo.numen.api.permission.EntityOwner}; nothing answered means nobody's), the per-companion mode
 * and the owner's own rule layer — checked before the factory layer — live with the owner
 * ({@link com.dwinovo.numen.api.permission.PermissionStore}). When a verdict asks, the task that proposed the
 * action consults the owner through the companion's
 * {@link com.dwinovo.numen.api.permission.ConsentDesk}; what the owner allows becomes a task-scoped grant that
 * feeds back into the next {@link com.dwinovo.numen.api.permission.Gate}, and "allow and remember" also writes
 * an allow row into the owner's layer. Design: {@code docs/permission-layer.md}.
 */
package com.dwinovo.numen.api.permission;
