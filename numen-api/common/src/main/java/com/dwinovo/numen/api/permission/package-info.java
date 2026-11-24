/**
 * <strong>Public API:</strong> the permission layer — the one place that answers "may the body
 * do this to the world". Content packs send an {@link com.dwinovo.numen.api.permission.Action}
 * through {@link com.dwinovo.numen.api.permission.Permission} (live, main thread) or a
 * {@link com.dwinovo.numen.api.permission.Gate} snapshot (any thread) and read the
 * {@link com.dwinovo.numen.api.permission.Verdict}; nothing else in the repository decides
 * whether a block may be broken, placed, or an entity attacked.
 *
 * <p>The layer itself knows no block, entity or item. Everything it knows comes from whoever registered to answer
 * ({@link com.dwinovo.numen.api.Questions#PERMIT}, an {@link com.dwinovo.numen.api.permission.Answerer} that says no,
 * ask, yes or nothing about one action on one object); the answers are merged in a fixed order by the plain-JVM module
 * {@code permission} ({@link com.dwinovo.numen.permission.Merge}): a no wins, then the owner's never-ask list, then an
 * ask, then a yes, and when nobody speaks the owner's tier for that kind of act decides. The one built-in answerer is
 * {@link com.dwinovo.numen.api.permission.Ownership}: whose a block or entity is comes from whoever registered to answer
 * that ({@link com.dwinovo.numen.api.permission.BlockOwner}, {@link com.dwinovo.numen.api.permission.EntityOwner};
 * nothing answered means nobody's). The owner's eight tiers and never-ask lists for each companion
 * ({@link com.dwinovo.numen.permission.Settings}) live with the owner
 * ({@link com.dwinovo.numen.api.permission.PermissionStore}). When a verdict asks, the task that proposed the
 * action consults the owner through the companion's
 * {@link com.dwinovo.numen.api.permission.ConsentDesk}; what the owner allows becomes a task-scoped grant that
 * feeds back into the next {@link com.dwinovo.numen.api.permission.Gate}, and "always allow" also puts the name on
 * the owner's list for that kind of act. Design: {@code docs/permission-layer.md}.
 */
package com.dwinovo.numen.api.permission;
